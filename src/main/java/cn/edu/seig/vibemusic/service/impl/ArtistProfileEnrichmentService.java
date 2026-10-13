package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistProfileEnrichmentStatus;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import cn.edu.seig.vibemusic.service.ArtistMetadataProvider.ArtistMetadata;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.util.ByteArrayMultipartFile;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class ArtistProfileEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ArtistProfileEnrichmentService.class);
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Pattern COMBINED_CREDIT =
            Pattern.compile("[、，,;；/]|\\b(?:feat\\.?|ft\\.?|featuring|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final String USER_AGENT =
            "JOJO-MUSIC/1.0 (https://github.com/timi669/jojo-music-server; artist profile enrichment)";

    private final ArtistMapper artistMapper;
    private final ArtistMetadataProvider metadataProvider;
    private final MinioService minioService;
    private final CacheManager cacheManager;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final AtomicReference<ArtistProfileEnrichmentStatus> currentJob = new AtomicReference<>();

    public ArtistProfileEnrichmentService(
            ArtistMapper artistMapper,
            ArtistMetadataProvider metadataProvider,
            MinioService minioService,
            CacheManager cacheManager,
            ExecutorService artistProfileEnrichmentExecutor,
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort
    ) {
        this.artistMapper = artistMapper;
        this.metadataProvider = metadataProvider;
        this.minioService = minioService;
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
            QueryWrapper<Artist> query = new QueryWrapper<>();
            query.and(wrapper -> wrapper.isNull("avatar").or().apply("TRIM(avatar) = ''")
                    .or().isNull("introduction").or().apply("TRIM(introduction) = ''"));
            List<Artist> artists = artistMapper.selectList(query);
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
            job.setCurrentArtist(null);
            job.setState("completed");
        } catch (Exception exception) {
            log.error("Artist profile enrichment job {} failed", job.getJobId(), exception);
            job.addIssue("批量任务失败：" + safeMessage(exception));
            job.setCurrentArtist(null);
            job.setState("failed");
        }
    }

    private void processArtist(Artist artist, ArtistProfileEnrichmentStatus job) {
        String name = artist.getArtistName() == null ? "" : artist.getArtistName().strip();
        if (name.isBlank() || COMBINED_CREDIT.matcher(name).find()) {
            job.setSkipped(job.getSkipped() + 1);
            job.addIssue(name + "：组合/合作艺人名称无法可靠匹配，已跳过");
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
            String introduction = isBlank(artist.getIntroduction()) ? metadata.introduction() : null;
            if (isBlank(avatar) && isBlank(introduction)) {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：未找到可用的自由授权头像或简介"
                        + (avatarDownloadFailed ? "（头像下载失败）" : ""));
                return;
            }

            int updated = artistMapper.fillMissingProfileFields(
                    artist.getArtistId(),
                    avatar,
                    avatar == null ? null : metadata.avatarSource(),
                    introduction,
                    introduction == null ? null : metadata.introductionSource()
            );
            if (updated > 0) {
                if (!isBlank(avatar)) {
                    job.setAvatarsAdded(job.getAvatarsAdded() + 1);
                }
                if (!isBlank(introduction)) {
                    job.setIntroductionsAdded(job.getIntroductionsAdded() + 1);
                }
            } else {
                job.setSkipped(job.getSkipped() + 1);
                job.addIssue(name + "：资料已由其他操作补全，未覆盖现有内容");
            }

            if (avatarDownloadFailed && !isBlank(introduction)) {
                job.addIssue(name + "：简介已补全，但头像下载失败");
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
