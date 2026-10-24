package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.mapper.ArtistMapper;
import cn.edu.seig.jojomusic.mapper.SongArtistMapper;
import cn.edu.seig.jojomusic.mapper.SongMapper;
import cn.edu.seig.jojomusic.model.dto.ArtistProfileEnrichmentStatus;
import cn.edu.seig.jojomusic.model.entity.Artist;
import cn.edu.seig.jojomusic.model.entity.Song;
import cn.edu.seig.jojomusic.model.entity.SongArtist;
import cn.edu.seig.jojomusic.service.ArtistMetadataProvider;
import cn.edu.seig.jojomusic.service.ArtistMetadataProvider.ArtistMetadata;
import cn.edu.seig.jojomusic.service.MinioService;
import cn.edu.seig.jojomusic.util.AudioArtistCreditResolver;
import cn.edu.seig.jojomusic.util.ByteArrayMultipartFile;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class ArtistProfileEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ArtistProfileEnrichmentService.class);
    /**
     * 头像下载的字节上限。放宽到 32 MB 是为了能接下数据源的大图再做缩放：
     * 实测网易云给王力宏的头像原图 9.08 MB（2756x2239），旧上限 8 MB 会直接拒绝，
     * 导致该歌手头像始终补不上。真正的体积控制在缩放环节（见 AVATAR_MAX_EDGE）。
     */
    private static final int MAX_DOWNLOAD_IMAGE_BYTES = 32 * 1024 * 1024;
    /** 头像缩放后的长边像素。界面最大用量百来像素，512 足够清晰且体积仅数十 KB。 */
    private static final int AVATAR_MAX_EDGE = 512;
    private static final int MAX_ARTIST_NAME_CODE_POINTS = 100;
    private static final long MAX_AUDIO_TAG_SCAN_BYTES = 250L * 1024 * 1024;
    private static final String USER_AGENT =
            "JOJO-MUSIC/1.0 (https://github.com/timi669/jojo-music-server; artist profile enrichment)";

    private final ArtistMapper artistMapper;
    private final SongArtistMapper songArtistMapper;
    private final SongMapper songMapper;
    private final ArtistMetadataProvider metadataProvider;
    private final MinioService minioService;
    private final MinioClient minioClient;
    private final String minioEndpoint;
    private final String minioBucket;
    private final CacheManager cacheManager;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final AtomicReference<ArtistProfileEnrichmentStatus> currentJob = new AtomicReference<>();

    public ArtistProfileEnrichmentService(
            ArtistMapper artistMapper,
            SongArtistMapper songArtistMapper,
            SongMapper songMapper,
            ArtistMetadataProvider metadataProvider,
            MinioService minioService,
            MinioClient minioClient,
            CacheManager cacheManager,
            ExecutorService artistProfileEnrichmentExecutor,
            @Value("${minio.endpoint}") String minioEndpoint,
            @Value("${minio.bucket}") String minioBucket,
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort
    ) {
        this.artistMapper = artistMapper;
        this.songArtistMapper = songArtistMapper;
        this.songMapper = songMapper;
        this.metadataProvider = metadataProvider;
        this.minioService = minioService;
        this.minioClient = minioClient;
        this.minioEndpoint = minioEndpoint;
        this.minioBucket = minioBucket;
        this.cacheManager = cacheManager;
        this.executor = artistProfileEnrichmentExecutor;
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyHost != null && !proxyHost.isBlank()) {
            httpClientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), proxyPort)));
        }
        this.httpClient = httpClientBuilder.build();
    }

    public synchronized ArtistProfileEnrichmentStatus start() {
        ArtistProfileEnrichmentStatus activeJob = currentJob.get();
        if (activeJob != null && "running".equals(activeJob.getState())) {
            return activeJob;
        }

        ArtistProfileEnrichmentStatus job = new ArtistProfileEnrichmentStatus();
        job.setJobId(UUID.randomUUID().toString());
        job.setState("running");
        currentJob.set(job);
        executor.execute(() -> run(job));
        return job;
    }

    public ArtistProfileEnrichmentStatus getStatus() {
        return currentJob.get();
    }

    private void run(ArtistProfileEnrichmentStatus job) {
        try {
            // 重置外源 Provider 的单轮状态（调用预算、熔断），否则跨轮累加会让国籍/性别不再被查询。
            if (metadataProvider instanceof CompositeArtistMetadataProvider compositeProvider) {
                compositeProvider.resetForNewRun();
            }
            List<Song> songs = songMapper.selectAudioArtistScanTargets();
            job.setAudioSongsTotal(songs.size());
            ArtistScanResult scanResult = scanAudioArtistNames(songs, job);
            job.setUniqueArtistsFound(scanResult.artistNames().size());
            Map<String, Artist> artistsByNormalizedName = addMissingArtists(scanResult.artistNames(), job);
            linkArtistsToSongs(scanResult.songIdsByArtist(), artistsByNormalizedName, songs, job);

            List<Artist> artists = artistMapper.selectProfileEnrichmentTargets();
            job.setTotal(artists.size());

            for (Artist artist : artists) {
                job.setCurrentArtist(artist.getArtistName());
                processArtist(artist, job);
                job.setProcessed(job.getProcessed() + 1);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Artist profile enrichment thread was interrupted");
                }
            }
            Cache cache = cacheManager.getCache("artistCache");
            if (cache != null) {
                cache.clear();
            }
            Cache songCache = cacheManager.getCache("songCache");
            if (songCache != null) {
                songCache.clear();
            }
            job.setCurrentArtist(null);
            job.setState("completed");
        } catch (Exception exception) {
            log.error("Artist profile enrichment job {} failed", job.getJobId(), exception);
            job.addIssue("批量任务失败：" + safeMessage(exception));
            job.setCurrentArtist(null);
            job.setState("failed");
        }
    }

    private ArtistScanResult scanAudioArtistNames(List<Song> songs, ArtistProfileEnrichmentStatus job) {
        Set<String> artistNames = new java.util.LinkedHashSet<>();
        Map<String, Set<Long>> songIdsByArtist = new LinkedHashMap<>();
        Map<String, List<String>> scannedObjects = new HashMap<>();
        job.setCurrentArtist("扫描 MinIO 音频标签");
        for (Song song : songs) {
            try {
                String objectName = minioObjectName(song.getAudioUrl());
                if (objectName == null || !isSupportedAudio(objectName)) {
                    continue;
                }
                List<String> credits = scannedObjects.get(objectName);
                if (credits == null) {
                    credits = readArtistTags(objectName);
                    scannedObjects.put(objectName, credits);
                    job.setAudioFilesScanned(job.getAudioFilesScanned() + 1);
                }
                for (String credit : credits) {
                    if (credit.codePointCount(0, credit.length()) > MAX_ARTIST_NAME_CODE_POINTS) {
                        job.setFailed(job.getFailed() + 1);
                        job.addIssue("歌曲 ID " + song.getSongId()
                                + "：标签艺人名称超过数据库 100 字符限制，已跳过");
                        continue;
                    }
                    String normalizedName = AudioArtistCreditResolver.normalize(credit);
                    artistNames.add(credit);
                    songIdsByArtist.computeIfAbsent(normalizedName, ignored -> new java.util.LinkedHashSet<>())
                            .add(song.getSongId());
                }
            } catch (Exception exception) {
                job.setFailed(job.getFailed() + 1);
                job.addIssue("歌曲 ID " + song.getSongId() + "：读取 MinIO 音频标签失败：" + safeMessage(exception));
                log.warn("Could not scan audio tags for song {}: {}", song.getSongId(), safeMessage(exception));
            } finally {
                job.setAudioSongsProcessed(job.getAudioSongsProcessed() + 1);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Artist metadata task was interrupted during MinIO scan");
            }
        }
        return new ArtistScanResult(artistNames, songIdsByArtist);
    }

    private String minioObjectName(String audioUrl) throws java.net.MalformedURLException {
        if (isBlank(audioUrl)) {
            return null;
        }
        URL objectUrl = new URL(audioUrl);
        URI endpointUri = URI.create(minioEndpoint);
        if (!"https".equalsIgnoreCase(objectUrl.getProtocol())
                && !"http".equalsIgnoreCase(objectUrl.getProtocol())) {
            throw new IllegalArgumentException("音频地址不是 HTTP(S) MinIO 地址");
        }
        if (objectUrl.getHost() == null
                || !objectUrl.getHost().equalsIgnoreCase(endpointUri.getHost())
                || objectUrl.getPort() != endpointUri.getPort()) {
            throw new IllegalArgumentException("音频地址不属于配置的 MinIO 服务");
        }
        String endpointPath = endpointUri.getRawPath() == null ? "" : endpointUri.getRawPath();
        while (endpointPath.endsWith("/")) {
            endpointPath = endpointPath.substring(0, endpointPath.length() - 1);
        }
        String prefix = endpointPath + "/" + minioBucket + "/";
        String rawPath = objectUrl.getPath();
        if (rawPath == null || !rawPath.startsWith(prefix)) {
            throw new IllegalArgumentException("音频地址不属于配置的 MinIO bucket");
        }
        String encodedObject = rawPath.substring(prefix.length());
        if (encodedObject.isBlank()) {
            throw new IllegalArgumentException("MinIO 音频对象名称为空");
        }
        return URLDecoder.decode(encodedObject.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private boolean isSupportedAudio(String objectName) {
        String normalized = objectName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".mp3") || normalized.endsWith(".flac");
    }

    private List<String> readArtistTags(String objectName) throws Exception {
        long objectSize = minioClient.statObject(StatObjectArgs.builder()
                .bucket(minioBucket)
                .object(objectName)
                .build()).size();
        if (objectSize <= 0 || objectSize > MAX_AUDIO_TAG_SCAN_BYTES) {
            throw new IOException("音频为空或超过 250 MB 的标签扫描限制");
        }
        String extension = objectName.toLowerCase(Locale.ROOT).endsWith(".flac") ? ".flac" : ".mp3";
        Path temporaryFile = Files.createTempFile("artist-credit-", extension);
        try {
            try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectName)
                    .build())) {
                Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }
            AudioFile audioFile = AudioFileIO.read(temporaryFile.toFile());
            Tag tag = audioFile.getTag();
            if (tag == null) {
                return List.of();
            }
            return List.copyOf(AudioArtistCreditResolver.resolve(tag.getAll(FieldKey.ARTIST)));
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private Map<String, Artist> addMissingArtists(Set<String> artistNames, ArtistProfileEnrichmentStatus job) {
        Map<String, Artist> existingArtists = new LinkedHashMap<>();
        for (Artist artist : artistMapper.selectArtistNamesForReconciliation()) {
            if (artist != null && !isBlank(artist.getArtistName())) {
                existingArtists.putIfAbsent(AudioArtistCreditResolver.normalize(artist.getArtistName()), artist);
            }
        }
        for (String artistName : artistNames) {
            String key = AudioArtistCreditResolver.normalize(artistName);
            if (key.isBlank() || existingArtists.containsKey(key)) {
                continue;
            }
            Artist artist = new Artist().setArtistName(artistName);
            try {
                artistMapper.insert(artist);
                existingArtists.put(key, artist);
                job.setArtistsAdded(job.getArtistsAdded() + 1);
            } catch (Exception exception) {
                Artist concurrentArtist = artistMapper.selectOne(
                        new QueryWrapper<Artist>().eq("name", artistName).last("LIMIT 1"));
                if (concurrentArtist == null) {
                    throw exception;
                }
                existingArtists.put(key, concurrentArtist);
            }
        }
        return existingArtists;
    }

    private void linkArtistsToSongs(
            Map<String, Set<Long>> songIdsByArtist,
            Map<String, Artist> artistsByNormalizedName,
            List<Song> songs,
            ArtistProfileEnrichmentStatus job
    ) {
        Map<Long, Song> songsById = new HashMap<>();
        for (Song song : songs) {
            songsById.put(song.getSongId(), song);
        }
        Map<Long, List<SongArtist>> songCredits = new HashMap<>();
        boolean relationTableUnavailable = false;
        for (Map.Entry<String, Set<Long>> artistEntry : songIdsByArtist.entrySet()) {
            Artist artist = artistsByNormalizedName.get(artistEntry.getKey());
            if (artist == null || artist.getArtistId() == null) {
                continue;
            }
            for (Long songId : artistEntry.getValue()) {
                if (relationTableUnavailable) {
                    return;
                }
                try {
                    List<SongArtist> credits = songCredits.computeIfAbsent(songId, id ->
                            songArtistMapper.selectList(new QueryWrapper<SongArtist>().eq("song_id", id)));
                    boolean alreadyLinked = credits.stream()
                            .anyMatch(credit -> artist.getArtistId().equals(credit.getArtistId()));
                    if (alreadyLinked) {
                        continue;
                    }
                    Song song = songsById.get(songId);
                    int nextOrder = credits.stream()
                            .map(SongArtist::getCreditOrder)
                            .filter(order -> order != null)
                            .mapToInt(Integer::intValue)
                            .max()
                            .orElse(0) + 1;
                    SongArtist link = new SongArtist()
                            .setSongId(songId)
                            .setArtistId(artist.getArtistId())
                            .setCreditOrder(nextOrder)
                            .setIsPrimary(song != null && artist.getArtistId().equals(song.getArtistId()) ? 1 : 0)
                            .setCreditSource("audio-tag");
                    songArtistMapper.insert(link);
                    credits.add(link);
                    job.setSongLinksAdded(job.getSongLinksAdded() + 1);
                } catch (Exception exception) {
                    relationTableUnavailable = true;
                    job.addIssue("无法写入歌曲艺人关联（请确认 tb_song_artist 迁移已执行）：" + safeMessage(exception));
                    log.warn("Could not link audio artist credits to songs: {}", safeMessage(exception));
                }
            }
        }
    }

    private record ArtistScanResult(Set<String> artistNames, Map<String, Set<Long>> songIdsByArtist) {
    }

    private void processArtist(Artist artist, ArtistProfileEnrichmentStatus job) {
        String name = artist.getArtistName() == null ? "" : artist.getArtistName().strip();
        if (name.isBlank()) {
            job.setSkipped(job.getSkipped() + 1);
            job.addIssue("歌手姓名为空，已跳过");
            return;
        }

        try {
            Optional<ArtistMetadata> result = metadataProvider.lookup(name);
            if (result.isEmpty()) {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：没有唯一且精确的 MusicBrainz 匹配或没有可用的维基资料");
                return;
            }

            ArtistMetadata metadata = result.get();
            String avatar = null;
            boolean avatarDownloadFailed = false;
            if (isBlank(artist.getAvatar()) && !isBlank(metadata.imageUrl())) {
                try {
                    avatar = downloadAvatar(metadata.imageUrl(), name);
                } catch (IOException | InterruptedException | RuntimeException exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    avatarDownloadFailed = true;
                    log.warn("Could not download Wikimedia avatar for '{}': {}", name, safeMessage(exception));
                }
            }
            String introduction = isBlank(metadata.introduction()) ? null : metadata.introduction();
            Integer gender = metadata.gender();
            String area = isBlank(metadata.area()) ? null : metadata.area();

            /*
             * 只补空缺，绝不覆盖已有资料。
             *
             * updateArtistProfileMetadata 的 SQL 是"字段非空即写"，若在这里不做拦截，
             * 重新跑一次补全就会把人工核对过的简介、正确的性别/国籍覆盖成数据源的值。
             * 头像此前已有同样的保护（上面的 isBlank(artist.getAvatar())），
             * 这里把简介/性别/国籍补齐到同一口径。
             */
            if (!isBlank(artist.getIntroduction())) {
                introduction = null;
            }
            if (artist.getGender() != null) {
                gender = null;
            }
            if (!isBlank(artist.getArea())) {
                area = null;
            }

            if (isBlank(avatar) && introduction == null && gender == null && area == null) {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：未找到可用的头像、简介、性别或国籍"
                        + (avatarDownloadFailed ? "（头像下载失败）" : "")
                        + (hasExistingProfile(artist) ? "（该歌手资料已齐备，按规则不覆盖）" : ""));
                return;
            }

            int updated = artistMapper.updateArtistProfileMetadata(
                    artist.getArtistId(),
                    avatar,
                    introduction,
                    gender,
                    area
            );
            if (updated > 0) {
                if (!isBlank(avatar)) {
                    job.setAvatarsAdded(job.getAvatarsAdded() + 1);
                }
                if (!isBlank(introduction)) {
                    job.setIntroductionsAdded(job.getIntroductionsAdded() + 1);
                }
                if (gender != null) {
                    job.setGendersAdded(job.getGendersAdded() + 1);
                }
                if (area != null) {
                    job.setAreasAdded(job.getAreasAdded() + 1);
                }
            } else {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：资料更新未影响数据库记录");
            }

            if (avatarDownloadFailed && (introduction != null || gender != null || area != null)) {
                job.addIssue(name + "：文字资料已更新，但头像下载失败");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.setFailed(job.getFailed() + 1);
            job.addIssue(name + "：任务线程被中断");
        } catch (Exception exception) {
            log.warn("Could not enrich artist profile for '{}': {}", name, safeMessage(exception));
            job.setFailed(job.getFailed() + 1);
            job.addIssue(name + "：" + safeMessage(exception));
        }
    }

    /**
     * 校验头像地址是否可信。
     *
     * <p>原实现只允许 Wikimedia 的图片域名，因为这曾经是唯一的资料来源。现在网易云与
     * 百度百科都是可用来源（{@code *.music.126.net}、{@code *.bcebos.com}），若沿用旧
     * 白名单会导致"资料找到了但头像全被拒绝下载"。因此改为按来源分别授权，
     * 仍然只接受明确可信的域名——不是放开成任意主机。
     */
    private void requireTrustedAvatarHost(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("头像链接必须是 HTTPS");
        }
        String host = uri.getHost();
        if (WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost(host)
                || NetEaseArtistMetadataProvider.isTrustedImageHost(host)
                || BaiduBaikeArtistMetadataProvider.isTrustedImageHost(host)) {
            return;
        }
        throw new IOException("头像链接不是受信任的图片地址：" + host);
    }

    private String downloadAvatar(String imageUrl, String artistName) throws IOException, InterruptedException {
        if (isBlank(imageUrl)) {
            return null;
        }
        URI uri = URI.create(imageUrl);
        requireTrustedAvatarHost(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("头像下载返回 HTTP " + response.statusCode());
        }

        String contentType;
        byte[] bytes;
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 源站的 Content-Type 实测不可信：网易云给王力宏的头像标注为 image/jpg，
            // 实际字节是 PNG；且原图 2756x2239、9.08 MB。因此这里放宽下载上限，
            // 真正的格式由文件头判定，体积由下面的缩放环节收敛。
            contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DOWNLOAD_IMAGE_BYTES) {
                    throw new IOException("歌手头像超过 " + (MAX_DOWNLOAD_IMAGE_BYTES / 1024 / 1024) + " MB，已拒绝下载");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length == 0) {
            throw new IOException("下载到的歌手头像为空");
        }
        String detected = detectImageExtension(contentType, bytes);
        if (detected == null) {
            throw new IOException("不支持的歌手头像格式：" + contentType);
        }

        /*
         * 统一缩放到 512 长边并转 JPEG。
         *
         * 为什么要缩放：数据源常给出远超显示所需的大图（实测 2756x2239、9.08 MB），
         * 而界面上的歌手头像最大也就百来像素。原实现直接拒绝超 8 MB 的图，
         * 导致王力宏这类歌手的头像始终补不上；同一张图缩到 512 后仅约 40 KB。
         * 转 JPEG 同时统一了格式，避开源站 Content-Type 标注错误的问题。
         */
        byte[] normalized = normalizeAvatarBytes(bytes);
        byte[] payload;
        String uploadType;
        String uploadExtension;
        if (normalized != null) {
            payload = normalized;
            uploadType = "image/jpeg";
            uploadExtension = ".jpg";
        } else {
            // 无法解码的图（损坏或非常规编码）退回原始字节，不因一张图阻断整个任务。
            payload = bytes;
            uploadType = "image/jpg".equals(contentType) ? "image/jpeg" : contentType;
            uploadExtension = detected;
        }
        String safeName = artistName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return minioService.uploadFile(
                new ByteArrayMultipartFile("avatar", safeName + uploadExtension, uploadType, payload),
                "artists"
        );
    }

    /** 供测试断言：头像格式识别与缩放。 */
    static byte[] normalizeAvatarBytesForTest(byte[] bytes) {
        return normalizeAvatarBytes(bytes);
    }

    /**
     * 把头像缩放到 {@link #AVATAR_MAX_EDGE} 长边以内并编码为 JPEG。
     *
     * @return 处理后的 JPEG 字节；无法解码时返回 null，由调用方决定如何降级
     */
    private static byte[] normalizeAvatarBytes(byte[] bytes) {
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(in);
            if (source == null) {
                return null;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            double scale = Math.min(1.0,
                    (double) AVATAR_MAX_EDGE / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            java.awt.image.BufferedImage target = new java.awt.image.BufferedImage(
                    targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = target.createGraphics();
            try {
                // PNG 可能带透明通道，铺白底避免转 JPEG 后出现黑块。
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                        java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                if (!javax.imageio.ImageIO.write(target, "jpg", out)) {
                    return null;
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * 判断头像图片格式，返回文件扩展名；无法识别时返回 null。
     *
     * <p>优先用文件头魔数判断，Content-Type 只作兜底。原因：实测网易云返回的是
     * {@code image/jpg}（非标准写法，标准为 {@code image/jpeg}），旧的严格白名单
     * 会把它判成不支持，导致 <b>62 个头像全部下载失败</b>——补全任务头像数始终为 0。
     * 有些来源甚至不返回 Content-Type，所以看字节比看头部更可靠。
     */
    private static String detectImageExtension(String contentType, byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        // 魔数无法识别时退回 Content-Type（容忍 jpg/jpeg 两种写法）。
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg", "image/pjpeg" -> ".jpg";
            case "image/png", "image/x-png" -> ".png";
            case "image/webp" -> ".webp";
            default -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 该歌手的头像/简介/性别/国籍是否都已有值（用于说明为何跳过）。 */
    private boolean hasExistingProfile(Artist artist) {
        return !isBlank(artist.getAvatar())
                && !isBlank(artist.getIntroduction())
                && artist.getGender() != null
                && !isBlank(artist.getArea());
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
