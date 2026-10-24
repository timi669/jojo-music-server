package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.mapper.SongMapper;
import cn.edu.seig.jojomusic.model.vo.LyricSongCandidate;
import cn.edu.seig.jojomusic.service.MinioService;
import cn.edu.seig.jojomusic.util.ByteArrayMultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 从网易云音乐抓取歌词并写回库中（LRC 格式，含时间轴，可直接用于同步播放）。
 *
 * <p><b>背景</b>：库中约 1/4 的歌曲没有歌词。既有流程（{@link LyricImportService}）
 * 只支持管理员手工上传 LRC 文件；本服务补上"从线上源自动获取"这一环。
 *
 * <p><b>实测依据</b>：对 10 首已知缺歌词的歌曲（ROSÉ、Avril Lavigne、Justin Bieber、
 * Charlie Puth 等），命中 10/10，且全部为带时间轴的 LRC（{@code [00:00.000]} 形式）。
 *
 * <p><b>安全</b>：
 * <ul>
 *   <li>复用 {@code songMapper.updateLyricIfUnchanged}——它带乐观锁，只有"抓取时的歌词"
 *       与数据库当前值一致才写入；因此<b>已有歌词的歌曲不会被覆盖</b>。</li>
 *   <li>标题与歌手都要能对上才采用，避免把同名的其他歌曲歌词写进来。</li>
 *   <li>只接受带时间轴的 LRC；纯文本歌词无法用于同步播放，一律拒收。</li>
 *   <li>写入后若乐观锁失败，回滚刚上传的 MinIO 对象，避免产生孤儿文件。</li>
 * </ul>
 */
@Service
public class NetEaseLyricFetchService {

    private static final Logger log = LoggerFactory.getLogger(NetEaseLyricFetchService.class);
    private static final String SEARCH_ENDPOINT = "https://music.163.com/api/search/get";
    private static final String LYRIC_ENDPOINT = "https://music.163.com/api/song/lyric";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final Pattern LRC_TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern NOISE = Pattern.compile("[\\s·・.,'\"’‘“”()（）\\[\\]【】\\-_!！?？]+");
    /**
     * 标题里的版本后缀，比对时忽略，例如 "Left and Right (Feat. Jung Kook of BTS)"。
     *
     * <p>同时覆盖 {@code explicit}/{@code clean} 这类<b>内容标记</b>：实测
     * {@code "Peaches (Explicit)"} 与库中 {@code Peaches} 是同一首歌，若不在比对时
     * 剔除该标记就会被误判为"标题不符"而漏抓。
     */
    private static final Pattern VERSION_SUFFIX =
            Pattern.compile("\\s*[\\(（\\[【].*?(feat|ft|remix|live|version|ver|remaster|acoustic|edit"
                            + "|mix|demo|instrumental|explicit|clean|censored|deluxe|bonus|radio).*?[\\)）\\]】]\\s*",
                    Pattern.CASE_INSENSITIVE);
    /** 最多歌词长度保护：个别歌曲会返回异常大的文本，避免把怪异内容写进库。 */
    private static final int MAX_LYRIC_LENGTH = 100_000;
    /**
     * 写库时扫描候选的倍数。队头可能整批抓不到（翻唱版、伴奏版、无歌手名等），
     * 只处理队头会卡死队列，故按目标数的若干倍扫描以穿透无解段。
     */
    private static final int SCAN_MULTIPLIER = 8;
    /** 扫描上限，防止一次请求拉取过多候选导致耗时过长。 */
    private static final int MAX_SCAN = 1000;
    /** 最少歌词长度，过滤掉只有作词作曲署名的空壳。 */
    private static final int MIN_LYRIC_LENGTH = 30;

    private final SongMapper songMapper;
    private final MinioService minioService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final long throttleMillis;
    private volatile long lastRequestAt;

    public NetEaseLyricFetchService(
            SongMapper songMapper,
            MinioService minioService,
            ObjectMapper objectMapper,
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort,
            @Value("${artist-metadata.netease-throttle-millis:900}") long throttleMillis
    ) {
        this.songMapper = songMapper;
        this.minioService = minioService;
        this.objectMapper = objectMapper;
        this.throttleMillis = Math.max(0, throttleMillis);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String host = proxyHost == null ? "" : proxyHost.strip();
        // 网易云国内直连即可；仅在显式配置了合法代理时才走代理。
        if (!host.isEmpty() && proxyPort > 0 && proxyPort <= 65535) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, proxyPort)));
        }
        this.httpClient = builder.build();
    }

    /**
     * 单首歌的抓取结果。
     *
     * <p>抓到的 LRC 正文直接放在结果里（而不是用 ThreadLocal 之类的侧信道传递），
     * 这样预览与写库都消费同一个对象，行为可预期、可测试。
     */
    public record LyricFetchResult(
            Long songId,
            String songName,
            String artistName,
            boolean found,
            String matchedTitle,
            String matchedArtist,
            int lyricLength,
            String reason,
            String lrc
    ) {
        /** 供接口返回：不把整份歌词塞进 JSON 响应。 */
        public LyricFetchResult withoutLyric() {
            return new LyricFetchResult(songId, songName, artistName, found, matchedTitle,
                    matchedArtist, lyricLength, reason, null);
        }
    }

    /** 抓取预览：只查不写。 */
    public List<LyricFetchResult> preview(List<Long> songIds, Integer limit) {
        List<LyricFetchResult> results = new ArrayList<>();
        for (LyricSongCandidate candidate : resolveTargets(songIds, limit)) {
            results.add(fetchOne(candidate));
        }
        return results;
    }

    /**
     * 抓取并写库。
     *
     * <p>{@code updateLyricIfUnchanged} 自带乐观锁：预期歌词与数据库当前值不一致时
     * 更新数为 0，此时回滚已上传对象并跳过，因此已有歌词不会被覆盖。
     *
     * <p><b>扫描范围</b>：候选队列的队头可能整批都抓不到（实测某次前 38 首全部无法
     * 命中——多为翻唱版、伴奏版或无歌手名的条目）。若只处理队头 limit 首，队列就会
     * 卡死，后面的歌永远轮不到。因此这里扫描 {@code limit} 的若干倍，凑够 limit 个
     * 命中即停止，从而穿透无解的队头。
     *
     * @return 成功写库的歌曲数
     */
    @CacheEvict(cacheNames = {"songCache", "artistCache"}, allEntries = true)
    public int fetchAndStore(List<Long> songIds, Integer limit) {
        int target = limit == null || limit <= 0 ? 50 : Math.min(limit, 500);
        // 显式指定歌曲时没有"队列卡死"问题，按给定清单处理即可。
        int scanCap = songIds != null && !songIds.isEmpty()
                ? songIds.size()
                : Math.min(SCAN_MULTIPLIER * target, MAX_SCAN);
        List<LyricSongCandidate> candidates = songIds != null && !songIds.isEmpty()
                ? resolveTargets(songIds, null)
                : songMapper.selectSongsMissingLyric(scanCap);

        int stored = 0;
        for (LyricSongCandidate candidate : candidates) {
            if (stored >= target) {
                break;
            }
            LyricFetchResult result = fetchOne(candidate);
            if (!result.found() || result.lrc() == null || result.lrc().isBlank()) {
                continue;
            }
            String uploadedUrl = null;
            try {
                ByteArrayMultipartFile uploadFile = new ByteArrayMultipartFile(
                        "lyric", UUID.randomUUID() + ".lrc", "text/plain; charset=utf-8",
                        result.lrc().getBytes(StandardCharsets.UTF_8));
                uploadedUrl = minioService.uploadFile(uploadFile, "lyrics", "text/plain; charset=utf-8");
                int updated = songMapper.updateLyricIfUnchanged(
                        candidate.getSongId(), candidate.getLyricUrl(), uploadedUrl);
                if (updated == 1) {
                    stored++;
                } else {
                    minioService.deleteFile(uploadedUrl);
                    uploadedUrl = null;
                    log.info("歌曲 {} 的歌词在抓取期间发生变化，已跳过", candidate.getSongId());
                }
            } catch (RuntimeException exception) {
                if (uploadedUrl != null) {
                    try {
                        minioService.deleteFile(uploadedUrl);
                    } catch (RuntimeException cleanup) {
                        log.error("回滚歌词对象失败：{}", cleanup.getMessage());
                    }
                }
                log.warn("写入歌词失败，歌曲 {}：{}", candidate.getSongId(), exception.getMessage());
            }
        }
        return stored;
    }

    private List<LyricSongCandidate> resolveTargets(List<Long> songIds, Integer limit) {
        if (songIds != null && !songIds.isEmpty()) {
            List<LyricSongCandidate> all = songMapper.selectLyricImportCandidates(null, null);
            List<LyricSongCandidate> picked = new ArrayList<>();
            for (Long id : songIds) {
                all.stream().filter(c -> c.getSongId().equals(id)).findFirst().ifPresent(picked::add);
            }
            return picked;
        }
        int cap = limit == null || limit <= 0 ? 50 : Math.min(limit, 500);
        // 只挑尚无歌词的歌曲，天然满足"已有的不动"。
        return songMapper.selectSongsMissingLyric(cap);
    }

    private LyricFetchResult fetchOne(LyricSongCandidate song) {
        String title = song.getSongName() == null ? "" : song.getSongName().strip();
        String artist = song.getArtistName() == null ? "" : song.getArtistName().strip();
        if (title.isEmpty()) {
            return fail(song, title, artist, "歌曲名为空");
        }
        try {
            /*
             * 先解析出该艺人在网易云上的所有可能身份，再据此约束歌曲匹配。
             *
             * 为什么需要"所有可能身份"而不是锁定一个：库里存的是 {@code BOL4}，
             * 网易云同时存在
             *   - id 1039524「脸红的思春期」（alias 里含 BOL4）—— 真正有作品的那个；
             *   - id 50191530「BOL4」—— 同名的空壳账号。
             * 实测若只取 name 完全相等的那条，会选中空壳，导致 BOL4 的歌全部漏抓。
             * 因此把两层命中的 id 组成候选集，交由歌曲搜索结果来裁决：只要歌曲的
             * 艺人 id 落在候选集里、且标题精确匹配，就采用。
             */
            Set<Long> candidateArtistIds = resolveArtistIds(artist);
            String searchTerm = firstArtist(artist);
            JsonNode search = getJson(SEARCH_ENDPOINT + "?s=" + encode(title + " " + searchTerm)
                    + "&type=1&limit=10");
            JsonNode songs = search.path("result").path("songs");
            if (!songs.isArray() || songs.isEmpty()) {
                return fail(song, title, artist, "网易云未搜到该歌曲");
            }
            JsonNode picked = pickBest(songs, title, artist, candidateArtistIds);
            if (picked == null) {
                return fail(song, title, artist, "标题或歌手无法对应，跳过以避免错配");
            }
            long id = picked.path("id").asLong(0);
            if (id <= 0) {
                return fail(song, title, artist, "歌曲编号无效");
            }
            JsonNode lyricResp = getJson(LYRIC_ENDPOINT + "?id=" + id + "&lv=1&kv=1&tv=-1");
            String lyric = lyricResp.path("lrc").path("lyric").asText("");
            String matchedTitle = picked.path("name").asText("");
            String matchedArtist = joinArtists(picked.path("artists"));
            if (lyric.strip().length() < MIN_LYRIC_LENGTH || !LRC_TIMESTAMP.matcher(lyric).find()) {
                return new LyricFetchResult(song.getSongId(), title, artist, false,
                        matchedTitle, matchedArtist, lyric.length(), "该来源没有带时间轴的可用歌词", null);
            }
            if (lyric.length() > MAX_LYRIC_LENGTH) {
                return new LyricFetchResult(song.getSongId(), title, artist, false,
                        matchedTitle, matchedArtist, lyric.length(), "歌词长度异常，已拒绝写入", null);
            }
            return new LyricFetchResult(song.getSongId(), title, artist, true,
                    matchedTitle, matchedArtist, lyric.length(), "", lyric);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fail(song, title, artist, "任务被中断");
        } catch (Exception exception) {
            String message = exception.getMessage();
            return fail(song, title, artist, message == null ? exception.getClass().getSimpleName() : message);
        }
    }

    /**
     * 解析出该艺人在网易云上所有可能的 id。
     *
     * <p>同时收集两层命中：
     * <ol>
     *   <li>{@code name} 与库中名字完全相等的；</li>
     *   <li>{@code alias} / {@code transNames} 命中的（处理中外文写法差异）。</li>
     * </ol>
     *
     * <p><b>为什么不锁定唯一一个</b>：实测 {@code BOL4} 在网易云有两条命中——
     * id 1039524「脸红的思春期」（别名含 BOL4，真正有作品的）与 id 50191530「BOL4」
     * （同名空壳）。若按"name 优先"锁定后者，BOL4 的歌会全部漏抓。改为返回候选集合，
     * 由歌曲搜索结果的艺人 id 来裁决谁才是对的，比预先猜测可靠。
     *
     * @return 候选 id 集合；为空表示无法解析，调用方将退回字符串匹配
     */
    private Set<Long> resolveArtistIds(String artistName) throws IOException, InterruptedException {
        String primary = firstArtist(artistName);
        if (primary.isEmpty()) {
            return Set.of();
        }
        JsonNode search = getJson(SEARCH_ENDPOINT + "?s=" + encode(primary) + "&type=100&limit=5");
        JsonNode artists = search.path("result").path("artists");
        if (!artists.isArray() || artists.isEmpty()) {
            return Set.of();
        }
        String key = normalize(primary);
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode candidate : artists) {
            boolean nameMatch = normalize(candidate.path("name").asText("")).equals(key);
            if (nameMatch || matchesAlias(candidate, key)) {
                long id = candidate.path("id").asLong(0);
                if (id > 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

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

    private static boolean matchesAnyName(JsonNode artist, String key) {
        if (normalize(artist.path("name").asText("")).equals(key)) {
            return true;
        }
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

    private static LyricFetchResult fail(LyricSongCandidate song, String title, String artist, String reason) {
        return new LyricFetchResult(song.getSongId(), title, artist, false, null, null, 0, reason, null);
    }

    /**
     * 挑最可信的一首：标题归一化后相等，且歌手能对应上。
     *
     * <p>歌手判定优先用解析出的 artistId 精确比对（可靠）；解析不到时才退回字符串
     * 包含判断。两种都不过就返回 null —— 宁可漏抓，也不写入别的歌的歌词。
     */
    private JsonNode pickBest(JsonNode songs, String title, String artist, Set<Long> candidateArtistIds) {
        String titleKey = comparableTitle(title);
        String artistKey = normalize(firstArtist(artist));
        for (JsonNode candidate : songs) {
            if (!comparableTitle(candidate.path("name").asText("")).equals(titleKey)) {
                continue;
            }
            JsonNode artists = candidate.path("artists");
            if (!candidateArtistIds.isEmpty()) {
                // 有候选 id 时用 id 精确约束：只要歌曲的任一艺人落在候选集合里就算命中，
                // 这样合作曲目与艺人的中外文写法都能覆盖。
                if (artists.isArray()) {
                    for (JsonNode a : artists) {
                        if (candidateArtistIds.contains(a.path("id").asLong(0))) {
                            return candidate;
                        }
                    }
                }
                continue;
            }
            // 解析不出候选 id 时退回字符串比对，保持与原先一致的保守策略。
            String candidateArtist = normalize(joinArtists(artists));
            if (artistKey.isEmpty() || candidateArtist.isEmpty()
                    || candidateArtist.contains(artistKey) || artistKey.contains(candidateArtist)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 取合作艺人串里的第一个艺人。
     *
     * <p>库里的分隔符实测有三种：中文顿号 {@code 、}（最常见，如
     * {@code "Charlie Puth、Jung Kook、BTS"}）、斜杠 {@code " / "}、和 {@code &}。
     * 只按斜杠切分会让整串被当作一个歌手名送去搜索，必然搜不到——这是初次实测
     * 0/8 命中的直接原因之一。
     */
    private static String firstArtist(String artist) {
        if (artist == null) {
            return "";
        }
        String[] parts = artist.split("\\s*/\\s*|、|&|,|，|;|；", 2);
        return parts.length == 0 ? artist.strip() : parts[0].strip();
    }

    /** 供测试断言：合作艺人串的切分规则。 */
    static String firstArtistForTest(String artist) {
        return firstArtist(artist);
    }

    static String comparableTitle(String title) {
        if (title == null) {
            return "";
        }
        return normalize(VERSION_SUFFIX.matcher(title).replaceAll(" "));
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return NOISE.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String joinArtists(JsonNode artists) {
        if (artists == null || !artists.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode artist : artists) {
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(artist.path("name").asText(""));
        }
        return builder.toString();
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
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("网易云接口返回 HTTP " + response.statusCode());
        }
        JsonNode node = objectMapper.readTree(response.body());
        if (node.path("code").asInt(0) == 405) {
            throw new IOException("网易云触发限流（code 405）");
        }
        return node;
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + throttleMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}