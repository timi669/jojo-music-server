package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistProfileEnrichmentStatus;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.SongArtist;
import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import cn.edu.seig.vibemusic.service.ArtistMetadataProvider.ArtistMetadata;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.util.AudioArtistCreditResolver;
import cn.edu.seig.vibemusic.util.ByteArrayMultipartFile;
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
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
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
            if (isBlank(avatar) && introduction == null && gender == null && area == null) {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：未找到可用的头像、简介、性别或国籍"
                        + (avatarDownloadFailed ? "（头像下载失败）" : ""));
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

    private String downloadAvatar(String imageUrl, String artistName) throws IOException, InterruptedException {
        if (isBlank(imageUrl)) {
            return null;
        }
        URI uri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost(uri.getHost())) {
            throw new IOException("头像链接不是受信任的 Wikimedia 图片地址");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Wikimedia 图片下载返回 HTTP " + response.statusCode());
        }

        String contentType;
        String extension;
        byte[] bytes;
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            extension = switch (contentType) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> throw new IOException("不支持的歌手头像格式：" + contentType);
            };
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IOException("歌手头像超过 8 MB，已拒绝下载");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length == 0) {
            throw new IOException("下载到的歌手头像为空");
        }
        String safeName = artistName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return minioService.uploadFile(
                new ByteArrayMultipartFile("avatar", safeName + extension, contentType, bytes),
                "artists"
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
