package cn.edu.seig.vibemusic.util;

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
    private static final Pattern MULTI_ARTIST_CREDIT = Pattern.compile("[;；/]");
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
