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
    /**
     * 个人性别的判据：性别字 + 文娱职业词。
     *
     * <p>必须成对出现，不能只匹配"男""女"——"男女对唱""女声和音"这类描述会误判。
     * 职业词表与 {@link #ENTERTAINMENT} 保持一致的文娱范围。
     */
    private static final Pattern MALE_ARTIST = Pattern.compile(
            "男(?:歌手|演员|艺人|模特|主持|舞者|音乐人|作曲家|词曲|说唱|表演者|导演|配音)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEMALE_ARTIST = Pattern.compile(
            "女(?:歌手|演员|艺人|模特|主持|舞者|音乐人|作曲家|词曲|说唱|表演者|导演|配音)",
            Pattern.CASE_INSENSITIVE);

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
        String area = extractNationality(entry);
        Integer gender = detectGender(summary + " " + desc);
        if (introduction.isEmpty() && imageUrl.isEmpty() && area == null && gender == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtistMetadata(imageUrl, introduction, gender, area));
    }

    /**
     * 剥掉百科资料卡值里的 HTML 与引用标注，得到纯文本。
     *
     * <p>百科的值形如
     * {@code <a ...>中国</a><sup>179</sup><a name="ref_179"></a>}。
     * <b>不能只删标签</b>——那样 {@code <sup>179</sup>} 的正文 "179" 会留下来，
     * 实测因此写出了 {@code "中国179"}、{@code "美国2"} 这种脏值。正确做法是先把
     * 「脚注上标 + 引用锚点」整段移除，再剥其余标签。
     */
    static String cleanCardValue(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw
                // <sup>179</sup> / <sup>[1]</sup> 这类脚注上标连同内容一起删掉
                .replaceAll("(?is)<sup\\b[^>]*>.*?</sup>", "")
                // <a name="ref_179"></a> 这类引用锚点（无正文，删掉即可）
                .replaceAll("(?is)<a\\b[^>]*name\\s*=\\s*\"?ref_[^\"]*\"?[^>]*>\\s*</a>", "")
                // 其余标签只剥壳、保留正文（如 <a href=...>中国</a> -> 中国）
                .replaceAll("<[^>]*>", "");
        return text.strip();
    }

    /**
     * 从词条资料卡里取「国籍」。
     *
     * <p><b>只认「国籍」</b>，不要「民族」：实测 {@code 谭维维}、{@code 薛黛霏} 的资料卡
     * 里「民族=汉族」——民族是族群归属而非国籍，写成地区会把"汉族"填进国籍字段。
     * 词条若无「国籍」字段则返回 null（留空好过填错）。
     */
    private static String extractNationality(JsonNode entry) {
        return cardText(entry, "国籍", "國家", "国家");
    }

    /**
     * 从资料卡里取指定字段的纯文本值。
     *
     * <p>多值字段（如多重国籍 {@code "美国、法国"}）只取第一个：库里既有的地区值都是
     * 单值，前端 {@code areaLabels} 映射也按单值设计，写入多值会让按国家筛选时
     * 匹配不到（{@code "美国、法国"} 既不是"美国"也不是"法国"）。取第一个即主国籍。
     *
     * @return 该字段的文本；不存在或为空时返回 null
     */
    private static String cardText(JsonNode entry, String... fieldNames) {
        JsonNode card = entry.path("card");
        if (!card.isArray()) {
            return null;
        }
        for (JsonNode item : card) {
            String fieldName = item.path("name").asText("");
            for (String wanted : fieldNames) {
                if (!wanted.equals(fieldName)) {
                    continue;
                }
                JsonNode value = item.path("value");
                String text;
                if (value.isArray()) {
                    StringBuilder builder = new StringBuilder();
                    for (JsonNode part : value) {
                        if (!builder.isEmpty()) {
                            builder.append(' ');
                        }
                        builder.append(part.asText(""));
                    }
                    text = builder.toString();
                } else {
                    text = value.asText("");
                }
                String cleaned = cleanCardValue(text);
                if (cleaned.isEmpty() || NO_ENTRY.equals(cleaned)) {
                    continue;
                }
                // 多值取第一个（分隔符实测有中文顿号与逗号）。
                String first = cleaned.split("、|,|，|/|;|；", 2)[0].strip();
                return first.isEmpty() ? cleaned : first;
            }
        }
        return null;
    }

    /**
     * 从简介文字里判断性别。
     *
     * <p>判据是「男/女 + 文娱职业」的组合（如"中国内地<b>女</b>歌手"、"澳大利亚<b>男</b>歌手"），
     * 而不是单独出现"男""女"二字——后者在"男女对唱""女声"这类描述里会误判。
     *
     * <p>取值约定与库里一致：{@code 0=男, 1=女}（见 tb_artist.gender 注释；
     * {@code 2} 专用于组合/乐队）。判断不出时返回 null，留空而不猜。
     */
    static Integer detectGender(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        /*
         * 先判个人，再判组合。
         *
         * 顺序很重要：实测 {@code 薛黛霏} 的简介是"中国内地女歌手、演员，女子演唱组合
         * 超级女团成员"——既含"女歌手"（说明本人是女歌手）又含"组合"（说明她所属的团体）。
         * 若先判组合会把她标成"组合/乐队"，与事实不符。以"本人是什么"优先，
         * 于是"女歌手"胜出；纯组合词条（如"中国内地男子演唱组合"）不含"男歌手/女歌手"
         * 这类个人表述，自然落到组合分支。
         */
        if (MALE_ARTIST.matcher(text).find()) {
            return 0;
        }
        if (FEMALE_ARTIST.matcher(text).find()) {
            return 1;
        }
        if (Pattern.compile("组合|乐队|乐团|演唱组合").matcher(text).find()) {
            return 2;
        }
        return null;
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