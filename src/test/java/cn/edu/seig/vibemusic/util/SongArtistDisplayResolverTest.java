package cn.edu.seig.vibemusic.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SongArtistDisplayResolverTest {

    @Test
    void shouldMergePrimaryAndCreditNamesWithoutDuplicates() {
        String displayName = SongArtistDisplayResolver.resolveDisplayName(
                "Adele",
                List.of("Adele", "Sam Smith", "Sam Smith", "", "  ")
        );

        assertEquals("Adele / Sam Smith", displayName);
    }

    @Test
    void shouldFallbackToPrimaryArtistWhenNoCreditsExist() {
        String displayName = SongArtistDisplayResolver.resolveDisplayName(
                "Taylor Swift",
                List.of()
        );

        assertEquals("Taylor Swift", displayName);
    }
}
