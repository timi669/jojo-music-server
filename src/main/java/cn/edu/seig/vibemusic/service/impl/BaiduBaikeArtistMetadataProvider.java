package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Pattern;

/**
 * 百度百科歌手资料提供者（简介 + 头像）。
 *
 * <p><b>为什么接百度百科</b>：中文歌手（谭维维、章子怡、元华、赵梦娇、薛黛霏等）在网易云
 * 往往查不到简介或只有一句话，而百度百科有完整摘要与词条图片。实测该开放接口可用：
 * {@code /api/openapi/BaikeLemmaCardApi?scope=103&format=json&appid=379020&bk_key=<名>&bk_length=600}
 * 返回 {@code title} / {@code abstract}（摘要）/ {@code image}（词条图）/ {@code desc}（头衔）。
 *
 * <p><b>身份确认（关键）</b>：百度百科按名字检索会命中错配条目——实测 {@code ROSÉ}
 * 返回的是篮球运动员"德里克·罗斯"，{@code Queen} 返回的是英语单词。因此必须做身份校验，
 * 规则为：
 * <ol>
 *   <li><b>标题归一化后与查询名相等</b>（如 {@code 谭维维} / {@code 赵梦娇} / {@code 元华}）；或</li>
 *   <li><b>摘要中出现查询名</b>——这正好覆盖"中文名 +（外文原名）"的写法：
 *       {@code 莉莉-罗丝·德普（Lily-Rose Depp）}、{@code 伊弗雷姆·赛克斯（Ephraim Sykes）}、
 *       {@code 麦当娜·西科尼（Madonna Ciccone）}。这类写法本身就声明了同一个人的两个名字，
 *       足以确认身份；</li>
 * </ol>
 * 并且摘要/头衔中要出现文娱类关键词（歌手/音乐/乐队/组合/演员/导演/模特/艺人等），
 * 以排除"英语单词""篮球运动员"这类同名的非艺人词条。
 */
@Service
public class BaiduBaikeArtistMetadataProvider implements ArtistMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(BaiduBaikeArtistMetadataProvider.class);
    private static final String ENDPOINT =
            "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi"
                    + "?scope=103&format=json&appid=379020&bk_length=600&bk_key=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    /**
     * 文娱类关键词：词条需命中其一，避免把同名的非艺人词条当歌手。
     *
     * <p>实测拦下过的错配：{@code Queen} → 词条"queen 是英语单词"、
     * {@code ROSÉ} → 篮球运动员"德里克·罗斯"。<b>但仅靠关键词不够</b>——
     * {@code Nate Smith} 的百科词条是"美国布景师"（描述里含"作品""电视剧"），
     * {@code Storm} 是"英文单词"（描述里含"雷电"）。因此还必须有
     * {@link #NON_ARTIST} 排除词兜底，两者配合才可靠。
     */
    private static final Pattern ENTERTAINMENT = Pattern.compile(
            "歌手|音乐|乐队|组合|乐团|主唱|作词|作曲|演员|导演|艺人|模特|主持|舞者|rapper|singer|"
                    + "musician|band|artist|songwriter|composer|actor|actress|dj|vocalist|producer",
            Pattern.CASE_INSENSITIVE);
    /**
     * 明确的非艺人词条特征，命中即排除。
     *
     * <p>实测案例：{@code 英语单词}、{@code 布景师}（美术工种）、{@code 天气现象}、
     * {@code 篮球运动员}。这些即使命中上面的文娱关键词也不能采用——把"美国布景师"
     * 的简介写成歌手简介是明显的错误数据。
     */
    private static final Pattern NON_ARTIST = Pattern.compile(
            "英语单词|汉语词语|词语|拼音|天气现象|气象|布景师|运动员|球星|篮球|足球|"
                    + "植物|动物|化学|物理|数学|疾病|药物|公司|品牌|软件|游戏名称",
            Pattern.CASE_INSENSITIVE);
    /** 百科返回的 title 占位值，表示没有命中词条。 */
    private static final String NO_ENTRY = "undefined";
    private static final Pattern NOISE = Pattern.compile("[\\s·・.,'\"’‘“”()（）\\[\\]【】\\-_]+");
    /** 简介有效性的最小文字字符数（排除 "[]"、"1111" 之类噪声）。 */
    private static final int MIN_LETTERS = 5;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long throttleMillis;
    private volatile long lastRequestAt;

    public BaiduBaikeArtistMetadataProvider(
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort,
            @Value("${artist-metadata.baike-throttle-millis:600}") long throttleMillis
    ) {
        this.throttleMillis = Math.max(0, throttleMillis);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String host = proxyHost == null ? "" : proxyHost.strip();
        // 百度百科国内直连即可；仅在显式配置了合法代理时才走代理。
        if (!host.isEmpty() && proxyPort > 0 && proxyPort <= 65535) {
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
        JsonNode entry = query(name);
        if (entry == null) {
            return Optional.empty();
        }
        String title = entry.path("title").asText("");
        String summary = entry.path("abstract").asText("");
        String desc = entry.path("desc").asText("");
        if (!isUsableEntry(name, title, summary, desc)) {
            log.debug("百度百科 '{}' 的词条不可用（title={} desc={}），已排除", name, title, desc);
            return Optional.empty();
        }

        String introduction = sanitizeIntroduction(summary);
        // 摘要本身太短时，用 desc（头衔）兜底——虽然简略，但比留空有用。
        if (introduction.isEmpty()) {
            introduction = sanitizeIntroduction(desc);
        }
        String imageUrl = entry.path("image").asText("");
        if (imageUrl.isBlank()) {
            imageUrl = "";
        }
        if (introduction.isEmpty() && imageUrl.isEmpty()) {
            return Optional.empty();
        }
        // 百度百科不提供性别/国籍/生日，这里留空交由其他来源或人工补充。
        return Optional.of(new ArtistMetadata(imageUrl, introduction, null, null));
    }

    private JsonNode query(String name) throws IOException, InterruptedException {
        throttle();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + encode(name)))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("百度百科接口返回 HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        // 未收录时返回空对象或非 JSON，按"无词条"处理而不是报错。
        int brace = body.indexOf('{');
        if (brace < 0) {
            return null;
        }
        return objectMapper.readTree(body.substring(brace));
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + throttleMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /**
     * 判断一个百科词条是否可以作为该歌手的资料。
     *
     * <p>供测试直接断言这三条规则：
     * <ol>
     *   <li>标题等于查询名，或摘要里出现查询名（覆盖"中文名（外文原名）"写法）；</li>
     *   <li>命中文娱关键词；</li>
     *   <li>不命中非艺人特征词。</li>
     * </ol>
     */
    static boolean isUsableEntry(String queryName, String title, String summary, String desc) {
        if (title == null || title.isBlank() || NO_ENTRY.equals(title)) {
            return false;
        }
        String key = normalize(queryName);
        boolean titleMatches = normalize(title).equals(key);
        boolean summaryNamesIt = normalize(summary == null ? "" : summary).contains(key);
        if (!titleMatches && !summaryNamesIt) {
            return false;
        }
        String combined = (summary == null ? "" : summary) + " " + (desc == null ? "" : desc);
        return ENTERTAINMENT.matcher(combined).find() && !NON_ARTIST.matcher(combined).find();
    }

    /**
     * 判断简介是否值得写入：过滤占位与噪声，但不按长度一刀切。
     *
     * <p>实测 {@code "1111111111"}、{@code "[]"} 这类无意义内容必须丢弃，而
     * {@code "专注于音乐创作的独立音乐人。"}（14 字）是有效简介，不能因短而拒绝。
     */
    static String sanitizeIntroduction(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return "";
        }
        String compact = text.replaceAll("[\\[\\]（）()【】]", "").strip();
        if (compact.isEmpty()) {
            return "";
        }
        if (compact.chars().distinct().count() <= 2) {
            return "";
        }
        long letters = compact.codePoints().filter(Character::isLetter).count();
        return letters >= MIN_LETTERS ? text : "";
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NOISE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 百度百科词条图片域名白名单。 */
    public static boolean isTrustedImageHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("bkimg.cdn.bcebos.com")
                || normalized.endsWith(".bcebos.com")
                || normalized.endsWith(".baidu.com");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}