package cn.edu.seig.jojomusic.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioArtistCreditResolverTest {

    @Test
    void separatesExplicitFeaturedAndSemicolonCreditsWithoutSplittingBandNames() {
        assertEquals(
                List.of("Taylor Swift", "Bon Iver", "Earth, Wind & Fire", "Michael Jackson"),
                List.copyOf(AudioArtistCreditResolver.resolve(List.of(
                        "Taylor Swift feat. Bon Iver",
                        "Earth, Wind & Fire; Michael Jackson",
                        "Various Artists"
                )))
        );
    }

    @Test
    void normalizesUnicodeAndWhitespaceForExistingArtistMatching() {
        assertEquals(
                AudioArtistCreditResolver.normalize("Ａｖｒｉｌ   Lavigne"),
                AudioArtistCreditResolver.normalize("Avril Lavigne")
        );
    }

    @Test
    void separatesSlashDelimitedCreditsAndRemovesBracketedSourceAnnotations() {
        assertEquals(
                List.of("Cher", "Lady Gaga", "AC/DC", "Justin Bieber"),
                List.copyOf(AudioArtistCreditResolver.resolve(List.of(
                        "Cher/Lady Gaga",
                        "AC/DC",
                        "Justin Bieber[music.migu.cn]"
                )))
        );
    }
}
