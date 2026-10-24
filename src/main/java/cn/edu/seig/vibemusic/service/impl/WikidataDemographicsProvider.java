package cn.edu.seig.vibemusic.service.impl;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从 Wikidata 取歌手的<b>国籍</b>与<b>性别</b>这两个结构化事实。
 *
 * <p><b>为什么不从文本里猜</b>：实测用"维基摘要首句里找国别词"的做法会写出错误数据——
 * {@code Jung Kook}、{@code JENNIE} 都被判成"日本"（实际韩国），{@code Twista}、
 * {@code Lil B} 被判成"组合"（实际个人男歌手）。国籍与性别是条目属性，应当读属性，
 * 不能做字符串推断。
 *
 * <p><b>为什么能挡掉同名错配</b>：Wikidata 按名字检索会命中无关实体——实测查
 * {@code 朱贺} 会得到 Q45476927（"明朝"）。因此要求实体必须带
 * <b>音乐类职业（P106）</b>或<b>乐队/组合类型（P31）</b>才采用；加上这一道门禁后
 * 该错配被挡掉，而 {@code Jung Kook / JENNIE / 2Pac / Lana Del Rey / Paul Simon}
 * 等都能正确取到国籍与性别。
 *
 * <p><b>取值范围</b>：性别沿用库里的约定 {@code 0=男、1=女、2=组合}；国籍归一化成
 * 前端 {@code areaLabels} 能识别的常用中文名（如"大韩民国"→"韩国"、
 * "美國"→"美国"），避免同一国家出现多种写法而把筛选拆成两个桶。
 */
@Service
public class WikidataDemographicsProvider {

    private static final Logger log = LoggerFactory.getLogger(WikidataDemographicsProvider.class);
    private static final String API = "https://www.wikidata.org/w/api.php";
    private static final String USER_AGENT = "jojo-music-data-enrichment/1.0 (local dev)";

    /** 音乐相关职业（P106）。命中其一才认为是歌手/音乐人。 */
    private static final Set<String> MUSIC_OCCUPATIONS = Set.of(
            "Q177220", "Q2643890", "Q639669", "Q36834", "Q855091", "Q486748", "Q753110",
            "Q158852", "Q488205", "Q2252262", "Q1430640", "Q183945", "Q1259917",
            "Q1198887", "Q386854", "Q646225", "Q13036893", "Q584301", "Q1964964");

    /** 乐队/组合的实例类型（P31）。 */
    private static final Set<String> ENSEMBLE_TYPES = Set.of(
            "Q215380", "Q2088357", "Q5741069", "Q641066", "Q9212979", "Q2082970");

    private static final String MALE_QID = "Q6581097";
    private static final String FEMALE_QID = "Q6581072";

    /** 国别名归一化：把 Wikidata 上五花八门的写法收敛成前端认识的常用中文名。 */
    private static final Map<String, String> COUNTRY_ALIASES = buildCountryAliases();

    private static final Pattern NOISE = Pattern.compile("[\\s·・.,'\"’‘“”()（）\\[\\]【】\\-_!！?？]+");

    /**
     * 请求间距。
     *
     * <p>Wikidata 对匿名调用限流较严，实测 250ms 间隔会大量触发 HTTP 429（一轮里 47 次），
     * 反而拖慢整轮。提到 1000ms 后稳定在每秒 1 次以内。
     */
    private volatile long throttleMillis;
    private final long baseThrottleMillis;
    private volatile long lastRequestAt;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 连续失败次数。一旦探测到 Wikidata 不可达（例如境外代理掉线），后续请求直接跳过，
     * 不再逐个等超时——实测代理不通时每位歌手都要白等约 20 秒，整轮补全被拖到无法接受。
     * 隔一段时间再放行探测，避免临时故障导致整轮彻底放弃。
     */
    private volatile int consecutiveFailures;
    private volatile long unavailableUntil;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MILLIS = 5 * 60 * 1000L;
    /** 429 时最多重试次数。 */
    private static final int MAX_RETRY_ON_429 = 1;
    /**
     * 单轮任务的调用预算。
     *
     * <p>Wikidata 限流严、单次调用含网络往返，实测不加限制会把整轮补全拖到几十分钟以上
     * （每位歌手 3 次调用 × 1 秒节流 + 429 退避）。这里给一个上限：用完即停止查询，
     * 剩下的人留到下次运行——补全是可重入的，多跑几轮即可逐步补齐，
     * 比让单轮长时间不结束更实用。
     */
    private static final int MAX_LOOKUPS_PER_RUN = 120;
    private final java.util.concurrent.atomic.AtomicInteger lookupsThisRun =
            new java.util.concurrent.atomic.AtomicInteger();

    public WikidataDemographicsProvider(
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort,
            @Value("${artist-metadata.wikidata-throttle-millis:1500}") long throttleMillis
    ) {
        this.baseThrottleMillis = Math.max(0, throttleMillis);
        this.throttleMillis = this.baseThrottleMillis;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String host = proxyHost == null ? "" : proxyHost.strip();
        // Wikidata 在境外，需要代理；未配置代理时直连（可能失败，由调用方降级处理）。
        if (!host.isEmpty() && proxyPort > 0 && proxyPort <= 65535) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, proxyPort)));
        }
        this.httpClient = builder.build();
    }

    /** 取到的两个字段，任一可为空。 */
    public record Demographics(String area, Integer gender) {
        public boolean isEmpty() {
            return (area == null || area.isBlank()) && gender == null;
        }
    }

    /**
     * 重置单轮预算与熔断状态。
     *
     * <p>必须由每次补全任务开始时调用：否则 {@link #lookupsThisRun} 会跨轮累加，
     * 第二轮回合起就被预算挡住，等于只生效一次。
     */
    public void resetForNewRun() {
        lookupsThisRun.set(0);
        consecutiveFailures = 0;
        unavailableUntil = 0;
    }

    /**
     * 查询某歌手的国籍与性别。
     *
     * <p>名字里带合作艺人时只看首位；带尾部括号译名时去括号后再试。
     */
    public Optional<Demographics> lookup(String artistName) throws IOException, InterruptedException {
        String name = artistName == null ? "" : artistName.strip();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        // 熔断：刚探测到不可用时直接返回，避免每位歌手都白等一次超时。
        if (System.currentTimeMillis() < unavailableUntil) {
            return Optional.empty();
        }
        // 预算：本轮调用次数用尽后不再查询，剩下的留待下次运行。
        if (lookupsThisRun.incrementAndGet() > MAX_LOOKUPS_PER_RUN) {
            return Optional.empty();
        }
        for (String candidate : nameVariants(name)) {
            String qid = findEntity(candidate);
            if (qid == null) {
                continue;
            }
            JsonNode claims = entityClaims(qid);
            if (claims == null) {
                continue;
            }
            if (!isMusicEntity(claims)) {
                log.debug("Wikidata '{}' 命中的 {} 不是音乐类实体，跳过", name, qid);
                continue;
            }
            String area = resolveArea(claims);
            Integer gender = resolveGender(claims);
            if (area == null && gender == null) {
                return Optional.empty();
            }
            return Optional.of(new Demographics(area, gender));
        }
        return Optional.empty();
    }

    /** 候选名：去尾部括号 -> 原首位艺人，逐个尝试。 */
    private static List<String> nameVariants(String raw) {
        List<String> out = new ArrayList<>();
        String head = raw.split("\\s*/\\s*|、|&|,|，|;|；", 2)[0].strip();
        String stripped = head.replaceFirst("\\s*[\\(（][^()（）]*[\\)）]\\s*$", "").strip();
        if (!stripped.isEmpty() && !out.contains(stripped)) {
            out.add(stripped);
        }
        if (!head.isEmpty() && !out.contains(head)) {
            out.add(head);
        }
        return out;
    }

    /** 按名字找唯一同名（或别名相同）的 Wikidata 实体。 */
    private String findEntity(String name) throws IOException, InterruptedException {
        /*
         * 只查一次。
         *
         * <p>原先按 en、zh 两种语言各查一次，每位歌手光搜索就发 2 个请求；加上取 claims 和取
         * 国籍标签，一共 3-4 个请求。Wikidata 对匿名调用限流很严（实测一轮 9 次 HTTP 429），
         * 请求越少越稳。用 {@code languagefallback} 让英文名优先、缺词条时回落到其它语言即可。
         */
        JsonNode root = getJson(API + "?action=wbsearchentities&format=json&limit=7&language=en"
                + "&uselang=en&search=" + encode(name));
        if (root == null) {
            return null;
        }
        JsonNode search = root.path("search");
        if (!search.isArray()) {
            return null;
        }
        for (JsonNode item : search) {
            if (nameMatches(item, name)) {
                String id = item.path("id").asText("");
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        return null;
    }

    private static boolean nameMatches(JsonNode item, String name) {
        String key = normalize(name);
        if (normalize(item.path("label").asText("")).equals(key)) {
            return true;
        }
        JsonNode aliases = item.path("aliases");
        if (aliases.isArray()) {
            for (JsonNode alias : aliases) {
                if (normalize(alias.asText("")).equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode entityClaims(String qid) throws IOException, InterruptedException {
        JsonNode root = getJson(API + "?action=wbgetentities&format=json&props=claims&ids=" + qid);
        if (root == null) {
            return null;
        }
        JsonNode claims = root.path("entities").path(qid).path("claims");
        return claims.isMissingNode() || claims.isNull() ? null : claims;
    }

    /** 要求命中音乐类职业或乐队类型，否则视为同名无关条目。 */
    private static boolean isMusicEntity(JsonNode claims) {
        for (String qid : claimIds(claims, "P106")) {
            if (MUSIC_OCCUPATIONS.contains(qid)) {
                return true;
            }
        }
        for (String qid : claimIds(claims, "P31")) {
            if (ENSEMBLE_TYPES.contains(qid)) {
                return true;
            }
        }
        return false;
    }

    /** 国籍：取 P27 的第一个，并归一化成前端认识的写法。 */
    private String resolveArea(JsonNode claims) throws IOException, InterruptedException {
        List<String> ids = claimIds(claims, "P27");
        if (ids.isEmpty()) {
            return null;
        }
        List<String> labels = entityLabels(ids);
        for (String label : labels) {
            String normalized = COUNTRY_ALIASES.get(label);
            if (normalized != null) {
                return normalized;
            }
        }
        // 未在归一化表里时，若本身就是中文地名则原样采用，否则放弃（避免写出英文国名）。
        for (String label : labels) {
            if (label != null && label.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN)) {
                return label;
            }
        }
        return null;
    }

    /** 性别：P21 男/女；若实例是乐队/组合则记为 2。 */
    private static Integer resolveGender(JsonNode claims) {
        for (String qid : claimIds(claims, "P21")) {
            if (MALE_QID.equals(qid)) {
                return 0;
            }
            if (FEMALE_QID.equals(qid)) {
                return 1;
            }
        }
        for (String qid : claimIds(claims, "P31")) {
            if (ENSEMBLE_TYPES.contains(qid)) {
                return 2;
            }
        }
        return null;
    }

    private static List<String> claimIds(JsonNode claims, String property) {
        List<String> out = new ArrayList<>();
        JsonNode list = claims.path(property);
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode claim : list) {
            String id = claim.path("mainsnak").path("datavalue").path("value").path("id").asText("");
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 批量取实体标签，优先中文。 */
    private List<String> entityLabels(List<String> qids) throws IOException, InterruptedException {
        if (qids.isEmpty()) {
            return List.of();
        }
        JsonNode root = getJson(API + "?action=wbgetentities&format=json&props=labels"
                + "&languages=zh|zh-hans|zh-cn|en&ids=" + String.join("|", qids));
        if (root == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String qid : qids) {
            JsonNode labels = root.path("entities").path(qid).path("labels");
            String picked = null;
            for (String lang : new String[]{"zh", "zh-hans", "zh-cn", "en"}) {
                String value = labels.path(lang).path("value").asText("");
                if (!value.isEmpty()) {
                    picked = value;
                    break;
                }
            }
            if (picked != null) {
                out.add(picked);
            }
        }
        return out;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        throttle();
        // URI.create 不接受未编码的 '|'，而 wbgetentities 的多值参数需要它（languages=zh|en、ids=Q1|Q2），
        // 因此创建 URI 时必须先编码，否则会抛 "Illegal character in query"——实测大量请求因此失败。
        URI uri = URI.create(url.replace("|", "%7C"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRY_ON_429; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException connectFailure) {
                noteFailure(connectFailure.getMessage());
                throw connectFailure;
            }
            if (response.statusCode() != 429) {
                break;
            }
            // 被限流：指数退避后再试；同时抬高后续请求的间距，避免继续撞限流。
            throttleMillis = Math.min(throttleMillis * 2, 5000);
            if (attempt < MAX_RETRY_ON_429) {
                Thread.sleep((attempt + 1) * 1500L);
            }
        }
        if (response.statusCode() == 429) {
            noteFailure("HTTP 429");
            throw new IOException("Wikidata 接口返回 HTTP 429（限流）");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            noteFailure("HTTP " + response.statusCode());
            throw new IOException("Wikidata 接口返回 HTTP " + response.statusCode());
        }
        consecutiveFailures = 0;
        // 成功后可缓慢回落到基准间距。
        if (throttleMillis > baseThrottleMillis) {
            throttleMillis = Math.max(baseThrottleMillis, throttleMillis - 100);
        }
        String body = response.body();
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }

    /** 记录失败；连续多次失败后进入冷却期，期间不再发起请求。 */
    private void noteFailure(String reason) {
        int failures = ++consecutiveFailures;
        if (failures >= FAILURE_THRESHOLD) {
            unavailableUntil = System.currentTimeMillis() + COOLDOWN_MILLIS;
            consecutiveFailures = 0;
            log.warn("Wikidata 连续 {} 次请求失败（{}），暂停 {} 分钟后再试",
                    failures, reason, COOLDOWN_MILLIS / 60000);
        }
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + throttleMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NOISE.matcher(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC))
                .replaceAll("").toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, String> buildCountryAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("美國", "美国");
        m.put("美国", "美国");
        m.put("United States of America", "美国");
        m.put("United States", "美国");
        m.put("中华人民共和国", "中国");
        m.put("中国", "中国");
        m.put("中國", "中国");
        m.put("大韩民国", "韩国");
        m.put("韓國", "韩国");
        m.put("韩国", "韩国");
        m.put("日本", "日本");
        m.put("日本国", "日本");
        m.put("英国", "英国");
        m.put("英國", "英国");
        m.put("加拿大", "加拿大");
        m.put("澳大利亚", "澳大利亚");
        m.put("澳洲", "澳大利亚");
        m.put("法國", "法国");
        m.put("法国", "法国");
        m.put("德國", "德国");
        m.put("德国", "德国");
        m.put("荷兰王国", "荷兰");
        m.put("荷兰", "荷兰");
        m.put("巴西", "巴西");
        m.put("牙买加", "牙买加");
        m.put("爱尔兰", "爱尔兰");
        m.put("新西兰", "新西兰");
        m.put("瑞典", "瑞典");
        m.put("挪威", "挪威");
        m.put("丹麦", "丹麦");
        m.put("西班牙", "西班牙");
        m.put("意大利", "意大利");
        m.put("墨西哥", "墨西哥");
        m.put("哥伦比亚", "哥伦比亚");
        m.put("波多黎各", "波多黎各");
        m.put("奈及利亞", "尼日利亚");
        m.put("尼日利亚", "尼日利亚");
        m.put("南非", "南非");
        m.put("印度", "印度");
        m.put("菲律賓", "菲律宾");
        m.put("菲律宾", "菲律宾");
        m.put("马来西亚", "马来西亚");
        m.put("新加坡", "新加坡");
        m.put("泰国", "泰国");
        m.put("俄罗斯", "俄罗斯");
        m.put("以色列", "以色列");
        m.put("黎巴嫩", "黎巴嫩");
        m.put("科摩罗", "科摩罗");
        m.put("巴巴多斯", "巴巴多斯");
        m.put("中華民國", "中国台湾");
        m.put("台湾", "中国台湾");
        return m;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}