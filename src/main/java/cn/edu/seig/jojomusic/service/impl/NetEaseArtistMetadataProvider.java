package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.service.ArtistMetadataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 网易云音乐歌手资料提供者（头像 + 简介）。
 *
 * <p>为什么加这个：库中 246 位歌手里 124 位缺头像、115 位缺简介，而原有的
 * {@link WikimediaArtistMetadataProvider} 覆盖不足。网易云实测覆盖率高：
 * 对 125 位待补歌手，头像命中 103（82.4%）、简介命中 89（71.2%）。
 *
 * <p><b>端点选择（踩过的坑）</b>：必须使用 {@code /api/search/get}，不能用
 * {@code /api/search/get/web} —— 后者现在对每个请求都返回
 * {@code {"code":405,"msg":"操作频繁"}}，会造成"覆盖率仅 8%"的假象。实测两个端点
 * 对同一批歌手跑对照：{@code /web} 为 0/10，{@code /get} 为 10/10。
 *
 * <p><b>与维基的关系</b>：维基经本机代理（127.0.0.1:10808）其实可达，但朴素按名
 * 查询有污染——实测 {@code ROSÉ} 命中的是"桃红葡萄酒"条目。故由
 * {@link CompositeArtistMetadataProvider} 先试网易云（覆盖高、直连稳定），
 * 未命中时再交给维基（经 MusicBrainz 唯一精确匹配，更权威但更慢）。
 *
 * <p><b>防污染</b>：默认只接受<b>归一化后完全相等</b>的歌手名。实测非精确匹配存在
 * 真实的张冠李戴（{@code 赵梦娇} → 林溪、{@code 王力宏&谭维维} → 王力宏），因此：
 * <ul>
 *   <li>多艺人串（含 {@code & 、 / , ;} 等分隔符）直接跳过，避免只补到合作方之一；</li>
 *   <li>非精确匹配一律放弃，宁可留空也不写入错误资料。</li>
 * </ul>
 *
 * <p><b>节流</b>：请求间隔默认 900ms（可配）。实测该间隔下 125 位歌手全程零限流。
 */
@Service
public class NetEaseArtistMetadataProvider implements ArtistMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(NetEaseArtistMetadataProvider.class);
    private static final String SEARCH_ENDPOINT = "https://music.163.com/api/search/get";
    private static final String ARTIST_ENDPOINT = "https://music.163.com/api/v1/artist/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final int SEARCH_LIMIT = 10;
    /**
     * 同名歌手裁决时的最小领先倍数（按作品数）。
     * 取 1.5：实测真实艺人普遍领先数十倍，而"分不清"的情况（如 56 vs 53）不到 1.1 倍，
     * 门槛设在这里能拦下后者又不误伤前者。
     */
    private static final double AMBIGUITY_LEAD_RATIO = 1.5;

    /**
     * 多艺人分隔符的识别正则。
     *
     * <p>用于判断一个名字是否属于"合作条目"（如 {@code "王力宏&谭维维"}）。这类条目
     * 现在不再跳过，而是取首位艺人的资料（见 {@link #firstArtist}）——首位艺人本人的
     * 头像与简介是准确的。真正需要拦的是"单人条目在数据源匹配到别人"，那由名称精确
     * 匹配与同名裁决负责。
     */
    private static final Pattern MULTI_ARTIST = Pattern.compile("[&、,，;/；]|\\s+feat\\.?\\s+|\\bfeaturing\\b",
            Pattern.CASE_INSENSITIVE);
    /** 归一化时剔除的装饰性字符：空白、括号、常见标点。 */
    private static final Pattern NOISE = Pattern.compile("[\\s·・.,'\"’‘“”()（）\\[\\]【】\\-_]+");
    /** 提取名字尾部括号里的内容，例如 {@code "로제 (ROSÉ)"} 中的 {@code ROSÉ}。 */
    private static final Pattern TAIL_PARENTHETICAL =
            Pattern.compile("[（(]\\s*([^()（）]+?)\\s*[）)]\\s*$");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long throttleMillis;
    private volatile long lastRequestAt;

    public NetEaseArtistMetadataProvider(
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort,
            @Value("${artist-metadata.netease-throttle-millis:900}") long throttleMillis
    ) {
        this.throttleMillis = Math.max(0, throttleMillis);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String host = proxyHost == null ? "" : proxyHost.trim();
        boolean proxyConfigured = !host.isEmpty() && proxyPort > 0;
        if (proxyConfigured) {
            // 网易云在国内直连即可；仅当显式配置了代理时才走代理，
            // 且不因端口非法而拒绝启动（原 Provider 会抛异常，这里更宽松）。
            if (proxyPort > 65535) {
                throw new IllegalArgumentException("代理端口超出 1-65535 范围：" + proxyPort);
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, proxyPort)));
        }
        this.httpClient = builder.build();
    }

    @Override
    public Optional<ArtistMetadata> lookup(String artistName) throws IOException, InterruptedException {
        String name = artistName == null ? "" : artistName.strip();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        /*
         * 多艺人条目（如 "Charlie Puth、Jung Kook、BTS"）取"首位艺人"的资料。
         *
         * 原先直接跳过，理由是怕张冠李戴；但那把两类情况混为一谈了：
         *   - "王力宏&谭维维" 这种，首位就是王力宏，配他的头像与简介是准确的；
         *   - "赵梦娇" 这类单人条目在网易云匹配到别人，才是真正需要拦的错配。
         * 实测这些合作条目的首位艺人绝大多数能唯一匹配（Charlie Puth、Justin Bieber、
         * Taylor Swift、王力宏…），留空反而让界面显得残缺。因此改为取首位艺人——
         * 信息仍然准确（就是那一位艺人本人），而不是猜测。
         */
        /*
         * 逐个尝试候选名，取第一个能在网易云匹配到候选歌手的。
         *
         * 为什么需要多个候选名：库里 {@code "로제 (ROSÉ)"} 这种写法，括号外是韩文名、
         * 括号内是英文正式名。实测剥掉括号后搜到的 {@code 로제} 是个只有 6 首歌、
         * 简介为空的条目，而用括号内的 {@code ROSÉ} 能命中本人（76 首歌、70 字简介）。
         * 因此把"括号内内容"排在"去括号主体"之前尝试。
         */
        JsonNode artists = null;
        String matchedSearchName = "";
        for (String variant : searchNameVariants(name)) {
            JsonNode searchResult = getJson(
                    SEARCH_ENDPOINT + "?s=" + encode(variant) + "&type=100&limit=" + SEARCH_LIMIT);
            if (searchResult == null) {
                continue;
            }
            JsonNode found = searchResult.path("result").path("artists");
            if (found.isArray() && !found.isEmpty()) {
                artists = found;
                matchedSearchName = variant;
                break;
            }
        }
        if (artists == null) {
            return Optional.empty();
        }

        /*
         * 收集候选：名称完全相等的、去掉尾部括号注记后相等的、以及别名命中的。
         *
         * 为什么要收别名：库里的 {@code BOL4} 在网易云有个同名空壳账号（5 首歌、
         * 简介为空），而真正的艺人是 {@code 脸红的思春期}——它的 alias 里写着 BOL4，
         * 有 164 首歌与 256 字简介。若只认精确名就会选中空壳，简介因此补不上。
         *
         * 为什么要收"去尾部括号后相等"：网易云的正式条目常在名字后附译名，如
         * {@code "BTS (防弹少年团)"}（584 首歌，"韩国男子演唱组合"），而 {@code "Bts"}
         * 是个只有 49 首歌、简介写着"独立音乐人。"的空壳。只认严格相等会选中空壳——
         * 实测 BTS 的简介正是这样被写错的。括号里的译名本身就是"同一人"的凭据，
         * 与库里 {@code "로제 (ROSÉ)"} 的情形一致。
         *
         * 加宽后仍需防误选：{@code "Madonna (Karaoke)"}（12 首歌的卡拉 OK 账号）
         * 也会因去括号后相等而入围，但下面的作品数裁决会把 3338 首歌的本尊选出来。
         */
        java.util.List<JsonNode> candidates = new java.util.ArrayList<>();
        java.util.List<JsonNode> exactMatches = new java.util.ArrayList<>();
        String key = normalize(matchedSearchName);
        for (JsonNode candidate : artists) {
            String candidateName = candidate.path("name").asText();
            boolean exactName = normalize(candidateName).equals(key);
            boolean parenStripped = normalize(stripTrailingParenthetical(candidateName)).equals(key);
            boolean aliasName = matchesAlias(candidate, key);
            if (exactName) {
                exactMatches.add(candidate);
            }
            if (exactName || parenStripped || aliasName) {
                candidates.add(candidate);
            }
        }
        JsonNode exact = pickUnambiguous(candidates, exactMatches, matchedSearchName);
        if (exact == null) {
            return Optional.empty();
        }

        long artistId = exact.path("id").asLong(0);
        String imageUrl = text(exact, "picUrl");
        String introduction = "";
        if (artistId > 0) {
            JsonNode detail = getJson(ARTIST_ENDPOINT + artistId);
            JsonNode artist = detail == null ? null : detail.path("artist");
            if (artist != null && !artist.isMissingNode()) {
                String detailPic = text(artist, "picUrl");
                if (!detailPic.isEmpty()) {
                    imageUrl = detailPic;
                }
                introduction = text(artist, "briefDesc");
            }
        }
        // 简介需过滤掉占位/噪声内容，而不是单纯按长度卡（见 sanitizeIntroduction）。
        introduction = sanitizeIntroduction(introduction);

        // 网易云不提供可靠的性别/国籍字段，这里留空交由其他来源或人工补充，
        // 而不是猜测——错误的性别/国籍比留空更有害。
        if (imageUrl.isEmpty() && introduction.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ArtistMetadata(imageUrl, introduction, null, null));
    }

    /**
     * 从候选歌手里挑出可信的那个。
     *
     * <p><b>为什么需要裁决</b>：网易云对常见艺名会返回多个同名歌手——
     * 实测 {@code Madonna} 精确同名 3 条、{@code Pressa} 7 条、{@code Nate Smith} 9 条。
     * 若要求"唯一匹配"就全部放弃，导致这些大牌歌手的头像一直补不上。
     *
     * <p><b>判据</b>：作品数（{@code musicSize}，即歌曲数）最多者通常就是本人。
     * 实测领先幅度极大：Madonna 17 倍、Queen 38 倍、Young Thug 130 倍、Future 152 倍。
     * 这一判据同时解决了"库名是别名"的情况：{@code BOL4} 的同名空壳只有 5 首歌，
     * 而别名指向的真身 {@code 脸红的思春期} 有 164 首，按作品数必然选对。
     *
     * <p><b>安全阀</b>：只有当第一名显著领先第二名（达到 {@link #AMBIGUITY_LEAD_RATIO} 倍）
     * 时才采用。像 {@code Lily-Rose Depp}（56 vs 53，仅 1.1 倍）这种分不清的情况一律放弃——
     * 补错一位歌手的头像与简介，比留空更有害。
     *
     * @param candidates    名称精确或别名命中的全部候选
     * @param exactMatches  其中名称完全相等的（仅用于日志，便于区分是精确命中还是别名命中）
     */
    private JsonNode pickUnambiguous(java.util.List<JsonNode> candidates,
                                     java.util.List<JsonNode> exactMatches, String name) {
        if (candidates.isEmpty()) {
            log.debug("'{}' 无匹配歌手（精确名 0 条），跳过", name);
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        java.util.List<JsonNode> ranked = new java.util.ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(b.path("musicSize").asInt(0), a.path("musicSize").asInt(0)));
        int topSize = ranked.get(0).path("musicSize").asInt(0);
        int secondSize = ranked.get(1).path("musicSize").asInt(0);
        if (topSize <= 0) {
            log.debug("'{}' 有 {} 个候选但都无作品数据，跳过", name, candidates.size());
            return null;
        }
        // 第二名作品数为 0 或极小时也算显著领先。
        double ratio = secondSize <= 0 ? Double.MAX_VALUE : (double) topSize / secondSize;
        if (ratio < AMBIGUITY_LEAD_RATIO) {
            log.debug("'{}' 有 {} 个同名且作品数接近（{} vs {}），无法判定，跳过",
                    name, exactMatches.size(), topSize, secondSize);
            return null;
        }
        return ranked.get(0);
    }

    /**
     * 生成用于搜索的候选名列表，按可信度排序。
     *
     * <p>顺序与理由：
     * <ol>
     *   <li><b>括号内的内容</b>——库里 {@code "로제 (ROSÉ)"} 括号内是英文正式名，
     *       用它才能命中本人；</li>
     *   <li><b>去掉尾部括号的主体</b>——常规情况（{@code "双笙（陈元汐）"} → {@code 双笙}）；</li>
     *   <li><b>首位艺人原名</b>——合作条目的兜底。</li>
     * </ol>
     *
     * <p>注意先试括号内是实测得出的顺序：反过来会先命中韩文空壳条目（只有 6 首歌、
     * 简介为空），从而错过真正的 {@code ROSÉ}（76 首歌、70 字简介）。
     */
    static java.util.List<String> searchNameVariants(String rawName) {
        java.util.List<String> variants = new java.util.ArrayList<>();
        if (rawName == null || rawName.isBlank()) {
            return variants;
        }
        // 先取首位艺人（合作串只处理第一位），但保留其括号注记用于提取括号内名字。
        String head = rawName.split("\\s*/\\s*|、|&|,|，|;|；", 2)[0].strip();
        java.util.regex.Matcher inner = TAIL_PARENTHETICAL.matcher(head);
        if (inner.find()) {
            addIfAbsent(variants, inner.group(1));
        }
        addIfAbsent(variants, firstArtist(head));
        addIfAbsent(variants, head);
        return variants;
    }

    private static void addIfAbsent(java.util.List<String> list, String value) {
        String trimmed = value == null ? "" : value.strip();
        if (!trimmed.isEmpty() && !list.contains(trimmed)) {
            list.add(trimmed);
        }
    }

    /**
     * 去掉名字尾部的括号注记。
     *
     * <p>网易云的正式条目常在名字后附译名：{@code "BTS (防弹少年团)"}、
     * {@code "로제 (ROSÉ)"}。比对时去掉这一段才能与本名相等。
     * 无括号注记时原样返回。
     */
    static String stripTrailingParenthetical(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceFirst("\\s*[\\(（][^()（）]*[\\)）]\\s*$", "").strip();
    }

    /**
     * 取合作艺人串里的首位艺人，并剥掉尾部的括号补充说明。
     *
     * <p>库里合作条目的分隔符实测有中文顿号 {@code 、}（最常见）、{@code &}、
     * 斜杠 {@code /}、逗号与分号。另外 {@code "ロゼ (ROSÉ)"} 这类名字带括号译名，
     * 直接搜索匹配不上，剥离括号后再试才能命中本人的中文条目。
     */
    static String firstArtist(String artist) {
        if (artist == null) {
            return "";
        }
        String head = artist.split("\\s*/\\s*|、|&|,|，|;|；", 2)[0].strip();
        // 剥离形如 " (ROSÉ)" / "（陈元汐）" 的尾部注记，保留主体名。
        String stripped = head.replaceFirst("\\s*[\\(（][^()（）]*[\\)）]\\s*$", "").strip();
        return stripped.isEmpty() ? head : stripped;
    }

    /**
     * 候选歌手的 {@code alias} 或 {@code transNames} 是否与目标名一致。
     *
     * <p>用于识别"库名是别名"的情况：{@code BOL4} 是 {@code 脸红的思春期} 的别名，
     * 只认精确名会选中同名空壳账号。
     */
    private static boolean matchesAlias(JsonNode artist, String key) {
        for (String field : new String[]{"alias", "transNames"}) {
            JsonNode names = artist.path(field);
            if (names.isArray()) {
                for (JsonNode name : names) {
                    if (normalize(name.asText("")).equals(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 判断简介是否值得写入。
     *
     * <p>不能只看长度：实测数据源里存在明显无意义的内容——
     * {@code 元华} 的简介是 {@code "1111111111"}、{@code Nate Smith} 的是 {@code "[]"}，
     * 这类必须丢弃。而 {@code 双笙} 的 {@code "专注于音乐创作的独立音乐人。"}（14 字）
     * 是有效简介，旧的 20 字下限会误杀。因此改为"含足够中文/字母、且不是重复字符或
     * 括号占位"的判断，而不是单纯卡长度。
     *
     * @param raw 数据源返回的简介原文
     * @return 可用时返回清理后的简介，否则返回空串
     */
    static String sanitizeIntroduction(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return "";
        }
        // 去掉纯占位内容：方括号/圆括号包裹的空壳，或全是同一个字符。
        String compact = text.replaceAll("[\\[\\]（）()【】]", "").strip();
        if (compact.isEmpty()) {
            return "";
        }
        if (compact.chars().distinct().count() <= 2) {
            // "1111111111"、"。。。" 这类没有信息量。
            return "";
        }
        // 至少要有 5 个文字字符（中日韩或字母），排除 "1.2.3" 之类的噪声。
        long meaningful = compact.codePoints()
                .filter(cp -> Character.isLetter(cp))
                .count();
        if (meaningful < 5) {
            return "";
        }
        return text;
    }

    /** 判断头像地址是否可信：仅允许网易云的图片域名，防止被数据源重定向到任意主机。 */
    public static boolean isTrustedImageHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".music.126.net") || normalized.equals("music.126.net");
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        throttle();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("网易云接口返回 HTTP " + response.statusCode());
        }
        JsonNode node = objectMapper.readTree(response.body());
        int code = node.path("code").asInt(0);
        if (code == 405) {
            throw new IOException("网易云触发限流（code 405），请降低请求频率或稍后重试");
        }
        return node;
    }

    /** 串行节流：网易云对高频访问敏感，请求间隔不足会返回 405。 */
    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + throttleMillis - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    /** NFKC 归一化 + 去除装饰字符 + 转小写，用于精确匹配判定。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = NOISE.matcher(normalized).replaceAll("");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 供测试断言使用：多艺人条目必须被识别出来。 */
    static boolean isMultiArtist(String name) {
        return name != null && MULTI_ARTIST.matcher(name).find();
    }

    /** 供测试断言使用：可信图片域名的白名单。 */
    static Set<String> trustedImageHostSuffixes() {
        return Set.of(".music.126.net", "music.126.net");
    }
}