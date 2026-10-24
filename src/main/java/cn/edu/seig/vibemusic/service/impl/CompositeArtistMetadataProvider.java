package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * 组合式歌手资料提供者：网易云 → 百度百科 → 维基百科（默认关闭）。
 *
 * <p><b>为什么需要组合</b>（均经实测，非推测）：
 * <ul>
 *   <li><b>网易云</b>：直连可达，头像覆盖 82.4%、简介 71.2%，是主力；但中文歌手常缺简介
 *       （实测 {@code 元华}、{@code Storm}、{@code Druski} 都是"有头像、简介 0 字"）。</li>
 *   <li><b>百度百科</b>：中文歌手的简介与词条图很全，实测补上了网易云查不到的
 *       {@code 赵梦娇}（360 字）、{@code Ephraim Sykes}（127 字）、
 *       {@code Lily-Rose Depp}（403 字）、{@code Giveon}（331 字）。</li>
 *   <li><b>维基链</b>：要求 MusicBrainz「唯一精确匹配」，门槛过严（实测同一批歌手仅 17% 通过），
 *       故默认关闭；如需其独有的性别/国籍字段可开启。</li>
 * </ul>
 *
 * <p><b>合并策略：按字段补齐，而不是"首个有结果即返回"</b>。这一点很关键——
 * 早先的写法遇到网易云只给头像、简介为空的情况就直接返回，导致百度百科里现成的简介
 * 白白浪费（实测 {@code 元华} 因此一直缺简介）。现在每个字段各自按来源顺序取第一个
 * 非空值：头像优先网易云，简介在网易云缺失时自动落到百度百科。归属依然清晰
 * （每个字段只取自一个来源），只是允许不同字段来自不同来源。
 */
@Service
@Primary
public class CompositeArtistMetadataProvider implements ArtistMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(CompositeArtistMetadataProvider.class);

    private final NetEaseArtistMetadataProvider netEaseProvider;
    private final BaiduBaikeArtistMetadataProvider baiduBaikeProvider;
    private final WikimediaArtistMetadataProvider wikimediaProvider;
    private final boolean wikimediaFallbackEnabled;

    public CompositeArtistMetadataProvider(
            NetEaseArtistMetadataProvider netEaseProvider,
            BaiduBaikeArtistMetadataProvider baiduBaikeProvider,
            WikimediaArtistMetadataProvider wikimediaProvider,
            @Value("${artist-metadata.wikimedia-fallback-enabled:false}") boolean wikimediaFallbackEnabled
    ) {
        this.netEaseProvider = netEaseProvider;
        this.baiduBaikeProvider = baiduBaikeProvider;
        this.wikimediaProvider = wikimediaProvider;
        this.wikimediaFallbackEnabled = wikimediaFallbackEnabled;
    }

    @Override
    public Optional<ArtistMetadata> lookup(String artistName) throws IOException, InterruptedException {
        String imageUrl = "";
        String introduction = "";
        Integer gender = null;
        String area = null;

        for (Source source : orderedSources()) {
            // 头像与简介都拿到了就不再问后续来源。性别/国籍只有维基提供，而维基默认关闭，
            // 若把它们也作为继续条件，会导致每位歌手都多问一次百度百科（实测约 600ms/次）。
            if (!imageUrl.isEmpty() && !introduction.isEmpty()) {
                break;
            }
            ArtistMetadata meta = attempt(source.provider(), artistName, source.label());
            if (meta == null) {
                continue;
            }
            if (imageUrl.isEmpty() && meta.imageUrl() != null && !meta.imageUrl().isBlank()) {
                imageUrl = meta.imageUrl();
            }
            if (introduction.isEmpty() && meta.introduction() != null && !meta.introduction().isBlank()) {
                introduction = meta.introduction();
            }
            if (gender == null && meta.gender() != null) {
                gender = meta.gender();
            }
            if (area == null && meta.area() != null && !meta.area().isBlank()) {
                area = meta.area();
            }
        }

        if (imageUrl.isEmpty() && introduction.isEmpty() && gender == null && area == null) {
            return Optional.empty();
        }
        log.info("'{}' 补齐结果：头像={} 简介={}字 性别={} 国籍={}", artistName,
                imageUrl.isEmpty() ? "无" : "有", introduction.length(),
                gender == null ? "无" : gender, area == null ? "无" : area);
        return Optional.of(new ArtistMetadata(imageUrl, introduction, gender, area));
    }

    private java.util.List<Source> orderedSources() {
        java.util.List<Source> sources = new java.util.ArrayList<>();
        sources.add(new Source(netEaseProvider, "网易云"));
        sources.add(new Source(baiduBaikeProvider, "百度百科"));
        if (wikimediaFallbackEnabled) {
            sources.add(new Source(wikimediaProvider, "维基百科"));
        }
        return sources;
    }

    private record Source(ArtistMetadataProvider provider, String label) {
    }

    /**
     * 调用单个来源，异常降级为"未命中"。
     *
     * <p>一个来源失败（超时、限流、结构变化）不应该让整条补全链断掉——否则单个站点的
     * 波动就会让整个任务停滞。失败会记日志，便于排查。
     */
    private ArtistMetadata attempt(ArtistMetadataProvider provider,
                                    String artistName, String sourceLabel) {
        try {
            return provider.lookup(artistName).orElse(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            log.info("{}查询 '{}' 失败，继续下一个来源：{}", sourceLabel, artistName, exception.getMessage());
            return null;
        }
    }
}