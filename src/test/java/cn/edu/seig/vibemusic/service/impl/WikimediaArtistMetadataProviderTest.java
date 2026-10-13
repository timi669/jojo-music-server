package cn.edu.seig.vibemusic.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaArtistMetadataProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsOnlyOneHighConfidenceExactArtistMatch() throws Exception {
        JsonNode candidates = objectMapper.readTree("""
                [
                  {"name":"Avril Lavigne","score":100},
                  {"name":"Avril Lavigne Tribute","score":92}
                ]
                """);

        assertTrue(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Avril Lavigne", candidates));
        assertFalse(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Avril", candidates));
    }

    @Test
    void rejectsAmbiguousAndLowConfidenceExactMatches() throws Exception {
        JsonNode ambiguous = objectMapper.readTree("""
                [
                  {"name":"Pink","score":100},
                  {"name":"Pink","score":96}
                ]
                """);
        JsonNode lowConfidence = objectMapper.readTree("""
                [{"name":"Muse","score":89}]
                """);

        assertFalse(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Pink", ambiguous));
        assertFalse(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Muse", lowConfidence));
    }

    @Test
    void rejectsQueriesWhoseFullResultSetExceedsTheUniquenessCheckLimit() throws Exception {
        JsonNode candidates = objectMapper.readTree("""
                {
                  "count": 101,
                  "artists": [{"name":"Coldplay","score":100}]
                }
                """);

        assertFalse(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Coldplay", candidates));
    }

    @Test
    void normalizesUnicodeAndWhitespaceForExactMatching() throws Exception {
        JsonNode candidates = objectMapper.readTree("""
                [{"name":"Ａｖｒｉｌ   Lavigne","score":95}]
                """);

        assertTrue(WikimediaArtistMetadataProvider.hasUniqueExactMatch("Avril Lavigne", candidates));
    }

    @Test
    void configuresOptionalHttpProxyForExternalMetadataRequests() {
        var directClient = WikimediaArtistMetadataProvider.createHttpClient("", 0);
        assertTrue(directClient.proxy().isEmpty());

        var proxiedClient = WikimediaArtistMetadataProvider.createHttpClient("127.0.0.1", 10809);
        Proxy proxy = proxiedClient.proxy().orElseThrow()
                .select(URI.create("https://www.wikidata.org"))
                .get(0);
        InetSocketAddress address = (InetSocketAddress) proxy.address();

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(10809, address.getPort());
        assertEquals(Proxy.Type.HTTP, proxy.type());
    }

    @Test
    void rejectsIncompleteHttpProxyConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> WikimediaArtistMetadataProvider.createHttpClient("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> WikimediaArtistMetadataProvider.createHttpClient("", 10809));
    }

    @Test
    void trustsWikimediaOriginalAndThumbnailImageHostsOnly() {
        assertTrue(WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost("upload.wikimedia.org"));
        assertTrue(WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost("thumb.wikimedia.org"));
        assertFalse(WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost("example.com"));
        assertFalse(WikimediaArtistMetadataProvider.isTrustedWikimediaImageHost("upload.wikimedia.org.example.com"));
    }
}
