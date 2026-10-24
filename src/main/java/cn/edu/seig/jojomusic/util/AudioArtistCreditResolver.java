package cn.edu.seig.jojomusic.util;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AudioArtistCreditResolver {

    private static final Pattern FEATURED_CREDIT =
            Pattern.compile("\\b(?:feat\\.?|ft\\.?|featuring|with)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * 多艺人分隔符。
     *
     * <p><b>2026-10-24 修正</b>：原先只有 {@code [;；/]}，漏了中文顿号 {@code 、} 与
     * 协作标记 {@code &}。后果是音频标签写成 {@code "王力宏、谭维维"} 或
     * {@code "王力宏&谭维维"} 时，整串被当作 <b>一个歌手名</b>存进 tb_artist，
     * 于是库里出现"把两位艺人合为一条"的记录。
     *
     * <p><b>为什么 {@code &} 要加"两侧无空格"的条件</b>：{@code &} 同时也是乐队名的
     * 组成部分——{@code "Earth, Wind & Fire"}、{@code "Simon & Garfunkel"} 都不该被拆开
     * （现有测试即覆盖此点）。而库里真实的协作写法 {@code "王力宏&谭维维"}、
     * {@code "R3HAB&VéRITé"} 两侧都没有空格。用 {@code (?<!\s)&(?!\s)} 即可两者兼顾。
     *
     * <p><b>为什么不把逗号列为分隔符</b>：{@code "Earth, Wind & Fire"} 含逗号，
     * 且 {@code "A, B"} 与 {@code "A,B"} 无法可靠区分是列表还是乐队名——宁可漏拆，
     * 也不能把乐队名拆错。中文顿号 {@code 、} 则无此歧义，可放心作为分隔符。
     */
    private static final Pattern MULTI_ARTIST_CREDIT =
            Pattern.compile("\\s*/\\s*|、|;|；|(?<!\\s)&(?!\\s)");
    private static final Pattern BRACKETED_SOURCE =
            Pattern.compile("\\s*\\[(?:https?://)?(?:www\\.)?[^\\]]*\\.[^\\]]*\\]");
    private static final String AC_DC_PLACEHOLDER = "\u0000";
    private static final Set<String> PLACEHOLDER_CREDITS =
            Set.of("various artists", "unknown", "unknown artist", "n/a");

    private AudioArtistCreditResolver() {
    }

    public static Set<String> resolve(Collection<String> tagValues) {
        Map<String, String> credits = new LinkedHashMap<>();
        if (tagValues == null) {
            return new LinkedHashSet<>();
        }
        for (String value : tagValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String protectedValue = value.replaceAll("(?i)AC\\s*/\\s*DC", AC_DC_PLACEHOLDER);
            for (String credit : MULTI_ARTIST_CREDIT.split(protectedValue)) {
                for (String featuredCredit : FEATURED_CREDIT.split(credit)) {
                    String normalized = featuredCredit.strip()
                            .replace(AC_DC_PLACEHOLDER, "AC/DC")
                            .replaceAll(BRACKETED_SOURCE.pattern(), "")
                            .replaceFirst("^[.,:]+\\s*", "")
                            .replaceAll("\\s+", " ");
                    if (!normalized.isBlank() && !PLACEHOLDER_CREDITS.contains(normalize(normalized))) {
                        credits.putIfAbsent(normalize(normalized), normalized);
                    }
                }
            }
        }
        return new LinkedHashSet<>(credits.values());
    }

    public static String normalize(String artistName) {
        return artistName == null
                ? ""
                : Normalizer.normalize(artistName, Normalizer.Form.NFKC)
                        .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
