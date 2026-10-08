package cn.edu.seig.vibemusic.util;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SongArtistDisplayResolver {

    private SongArtistDisplayResolver() {
    }

    public static String resolveDisplayName(String primaryArtistName, Collection<String> additionalArtistNames) {
        Set<String> names = new LinkedHashSet<>();
        addArtistName(names, primaryArtistName);

        if (additionalArtistNames != null) {
            for (String artistName : additionalArtistNames) {
                addArtistName(names, artistName);
            }
        }

        if (names.isEmpty()) {
            return "";
        }

        return String.join(" / ", names);
    }

    private static void addArtistName(Set<String> names, String artistName) {
        if (artistName == null) {
            return;
        }

        String normalized = artistName.trim();
        if (normalized.isEmpty()) {
            return;
        }

        normalized = normalized.replaceAll("\\s+", " ");
        names.add(normalized);
    }
}
