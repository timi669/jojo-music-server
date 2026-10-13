package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class WikimediaArtistMetadataProvider implements ArtistMetadataProvider {

    private static final int MINIMUM_MUSICBRAINZ_SCORE = 90;
    private static final int MUSICBRAINZ_RESULT_LIMIT = 100;
    private static final List<String> PREFERRED_WIKIPEDIA_LANGUAGES =
            List.of("en", "zh", "ja", "ko", "fr", "de", "es", "pt", "it");
    private static final Pattern WIKIDATA_ID = Pattern.compile("Q[1-9][0-9]*");
    private static final String USER_AGENT =
            "JOJO-MUSIC/1.0 (https://github.com/timi669/jojo-music-server; artist profile enrichment)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long lastMusicBrainzRequestAt;

    public WikimediaArtistMetadataProvider(
            @Value("${artist-metadata.proxy-host:}") String proxyHost,
            @Value("${artist-metadata.proxy-port:0}") int proxyPort
    ) {
        this.httpClient = createHttpClient(proxyHost, proxyPort);
    }

    static HttpClient createHttpClient(String proxyHost, int proxyPort) {
        String normalizedHost = proxyHost == null ? "" : proxyHost.trim();
        if (normalizedHost.isEmpty() && proxyPort == 0) {
            return baseHttpClientBuilder().build();
        }
        if (normalizedHost.isEmpty() || proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException(
                    "Both a proxy host and a valid proxy port (1-65535) must be configured."
            );
        }
        return baseHttpClientBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(normalizedHost, proxyPort)))
                .build();
    }

    private static HttpClient.Builder baseHttpClientBuilder() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
    }

    @Override
    public Optional<ArtistMetadata> lookup(String artistName) throws IOException, InterruptedException {
        JsonNode searchResults = musicBrainzSearch(artistName);
        if (!hasUniqueExactMatch(artistName, searchResults)) {
            return Optional.empty();
        }
        List<JsonNode> exactMatches = new ArrayList<>();
        for (JsonNode candidate : searchResults.path("artists")) {
            if (normalize(candidate.path("name").asText()).equals(normalize(artistName))) {
                exactMatches.add(candidate);
            }
        }

        String artistId = exactMatches.get(0).path("id").asText();
        if (artistId.isBlank()) {
            return Optional.empty();
        }

        JsonNode artistDetails = musicBrainzArtistDetails(artistId);
        String wikidataId = findWikidataId(artistDetails);
        String wikipediaUrl = findWikipediaUrl(artistDetails);
        WikidataProfile wikidataProfile = wikidataId.isBlank()
                ? WikidataProfile.empty()
                : wikidataProfile(wikidataId);

        if (wikipediaUrl.isBlank()) {
            wikipediaUrl = wikidataProfile.wikipediaUrl();
        }
        WikiSummary summary = wikipediaUrl.isBlank() ? WikiSummary.empty() : wikipediaSummary(wikipediaUrl);
        String imageName = wikidataProfile.imageName();
        if (imageName.isBlank() && summary.imageName() != null) {
            imageName = summary.imageName();
        }
        CommonsImage commonsImage = imageName.isBlank() ? CommonsImage.empty() : commonsImage(imageName);

        if (summary.introduction().isBlank() && commonsImage.imageUrl().isBlank()) {
            return Optional.empty();
        }
        String biographySource = summary.introduction().isBlank() ? "" : summary.sourceUrl();
        return Optional.of(new ArtistMetadata(
                commonsImage.imageUrl(),
                commonsImage.sourceUrl(),
                summary.introduction(),
                biographySource
        ));
    }

    private JsonNode musicBrainzSearch(String artistName) throws IOException, InterruptedException {
        String query = "artist:\"" + artistName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        URI uri = URI.create("https://musicbrainz.org/ws/2/artist/?query="
                + encode(query) + "&fmt=json&limit=" + MUSICBRAINZ_RESULT_LIMIT);
        return getJson(uri, true);
    }

    private JsonNode musicBrainzArtistDetails(String artistId) throws IOException, InterruptedException {
        URI uri = URI.create("https://musicbrainz.org/ws/2/artist/" + encode(artistId)
                + "?inc=url-rels&fmt=json");
        return getJson(uri, true);
    }

    private WikidataProfile wikidataProfile(String wikidataId) throws IOException, InterruptedException {
        if (!WIKIDATA_ID.matcher(wikidataId).matches()) {
            return WikidataProfile.empty();
        }
        JsonNode entityData = getJson(
                URI.create("https://www.wikidata.org/wiki/Special:EntityData/" + wikidataId + ".json"),
                false
        );
        JsonNode entity = entityData.path("entities").path(wikidataId);
        String imageName = entity.path("claims").path("P18").path(0)
                .path("mainsnak").path("datavalue").path("value").asText("");
        String wikipediaUrl = preferredWikipediaSitelink(entity.path("sitelinks"));
        return new WikidataProfile(imageName, wikipediaUrl);
    }

    private String preferredWikipediaSitelink(JsonNode sitelinks) {
        for (String language : PREFERRED_WIKIPEDIA_LANGUAGES) {
            String title = sitelinks.path(language + "wiki").path("title").asText("");
            if (!title.isBlank()) {
                return "https://" + language + ".wikipedia.org/wiki/" + encode(title.replace(' ', '_'));
            }
        }
        return "";
    }

    private WikiSummary wikipediaSummary(String wikipediaUrl) throws IOException, InterruptedException {
        URI articleUri = URI.create(wikipediaUrl);
        String host = articleUri.getHost();
        if (!"https".equalsIgnoreCase(articleUri.getScheme())
                || host == null
                || !host.matches("[a-z0-9-]+\\.wikipedia\\.org")
                || !articleUri.getRawPath().startsWith("/wiki/")) {
            return WikiSummary.empty();
        }
        String language = host.substring(0, host.indexOf(".wikipedia.org"));
        String title = articleUri.getRawPath().substring("/wiki/".length());
        URI summaryUri = URI.create("https://" + language + ".wikipedia.org/api/rest_v1/page/summary/" + title);
        JsonNode response = getJson(summaryUri, false);
        String extract = truncate(response.path("extract").asText("").trim(), 1000);
        String sourceUrl = response.path("content_urls").path("desktop").path("page").asText(wikipediaUrl);
        String thumbnail = response.path("thumbnail").path("source").asText("");
        if (thumbnail.isBlank()) {
            thumbnail = response.path("originalimage").path("source").asText("");
        }
        String imageName = commonsFileNameFromUploadUrl(thumbnail);
        return new WikiSummary(extract, sourceUrl, imageName);
    }

    private CommonsImage commonsImage(String imageName) throws IOException, InterruptedException {
        String title = "File:" + imageName;
        URI uri = URI.create("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo"
                + "&iiprop=url%7Cextmetadata&iiurlwidth=800&titles=" + encode(title));
        JsonNode pages = getJson(uri, false).path("query").path("pages");
        if (!pages.isObject()) {
            return CommonsImage.empty();
        }
        JsonNode page = pages.elements().hasNext() ? pages.elements().next() : null;
        if (page == null || page.has("missing")) {
            return CommonsImage.empty();
        }
        JsonNode imageInfo = page.path("imageinfo").path(0);
        JsonNode metadata = imageInfo.path("extmetadata");
        String license = metadata.path("LicenseShortName").path("value").asText("").trim();
        if (license.isBlank() || license.toLowerCase().contains("non-free")) {
            return CommonsImage.empty();
        }
        String imageUrl = imageInfo.path("thumburl").asText(imageInfo.path("url").asText(""));
        String sourceUrl = imageInfo.path("descriptionurl").asText("");
        if (sourceUrl.isBlank()) {
            return CommonsImage.empty();
        }
        URI imageUri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(imageUri.getScheme())
                || !isTrustedWikimediaImageHost(imageUri.getHost())) {
            return CommonsImage.empty();
        }
        return new CommonsImage(imageUrl, sourceUrl);
    }

    private JsonNode getJson(URI uri, boolean musicBrainz) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (musicBrainz) {
                waitForMusicBrainzRateLimit();
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(25))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readTree(response.body());
            }
            if ((response.statusCode() == 429 || response.statusCode() == 503) && attempt < 2) {
                Thread.sleep(retryDelay(response, attempt));
                continue;
            }
            throw new IOException("Metadata provider returned HTTP " + response.statusCode() + " for " + uri.getHost());
        }
        throw new IOException("Metadata provider retry limit reached for " + uri.getHost());
    }

    private synchronized void waitForMusicBrainzRateLimit() throws InterruptedException {
        long waitMillis = 1100 - (System.currentTimeMillis() - lastMusicBrainzRequestAt);
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
        lastMusicBrainzRequestAt = System.currentTimeMillis();
    }

    private long retryDelay(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        try {
            return Math.min(30_000, Math.max(1000, Long.parseLong(retryAfter) * 1000));
        } catch (NumberFormatException ignored) {
            return 1500L * (attempt + 1);
        }
    }

    private String findWikidataId(JsonNode artistDetails) {
        for (JsonNode relation : artistDetails.path("relations")) {
            if (!"wikidata".equalsIgnoreCase(relation.path("type").asText())) {
                continue;
            }
            String resource = relation.path("url").path("resource").asText("");
            String id = resource.substring(resource.lastIndexOf('/') + 1);
            if (WIKIDATA_ID.matcher(id).matches()) {
                return id;
            }
        }
        return "";
    }

    private String findWikipediaUrl(JsonNode artistDetails) {
        List<String> urls = new ArrayList<>();
        for (JsonNode relation : artistDetails.path("relations")) {
            if ("wikipedia".equalsIgnoreCase(relation.path("type").asText())) {
                String resource = relation.path("url").path("resource").asText("");
                if (resource.startsWith("https://") && resource.contains(".wikipedia.org/wiki/")) {
                    urls.add(resource);
                }
            }
        }
        return urls.stream()
                .min(Comparator.comparingInt(this::languagePriority))
                .orElse("");
    }

    private int languagePriority(String url) {
        for (int index = 0; index < PREFERRED_WIKIPEDIA_LANGUAGES.size(); index++) {
            if (url.startsWith("https://" + PREFERRED_WIKIPEDIA_LANGUAGES.get(index) + ".wikipedia.org/")) {
                return index;
            }
        }
        return PREFERRED_WIKIPEDIA_LANGUAGES.size();
    }

    private String commonsFileNameFromUploadUrl(String imageUrl) {
        if (imageUrl.isBlank()) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException exception) {
            return "";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !isTrustedWikimediaImageHost(uri.getHost())) {
            return "";
        }
        String path = uri.getRawPath();
        int marker = path.indexOf("/commons/");
        if (marker < 0) {
            return "";
        }
        String[] segments = path.substring(marker + "/commons/".length()).split("/");
        if (segments.length < 3) {
            return "";
        }
        String encodedName = segments[segments.length - 1];
        if ("thumb".equals(segments[0])) {
            if (segments.length < 5) {
                return "";
            }
            encodedName = segments[segments.length - 2];
        }
        try {
            return java.net.URLDecoder.decode(encodedName, StandardCharsets.UTF_8).replace('_', ' ');
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    static boolean isTrustedWikimediaImageHost(String host) {
        return "upload.wikimedia.org".equalsIgnoreCase(host)
                || "thumb.wikimedia.org".equalsIgnoreCase(host);
    }

    static boolean hasUniqueExactMatch(String artistName, JsonNode candidates) {
        if (candidates.path("count").asInt(0) > MUSICBRAINZ_RESULT_LIMIT) {
            return false;
        }
        JsonNode artistCandidates = candidates.isArray() ? candidates : candidates.path("artists");
        int exactMatches = 0;
        int selectedScore = 0;
        for (JsonNode candidate : artistCandidates) {
            if (normalize(candidate.path("name").asText()).equals(normalize(artistName))) {
                exactMatches++;
                selectedScore = candidate.path("score").asInt();
            }
        }
        return exactMatches == 1 && selectedScore >= MINIMUM_MUSICBRAINZ_SCORE;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String truncate(String value, int maximumCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints)).stripTrailing() + "…";
    }

    private record WikidataProfile(String imageName, String wikipediaUrl) {
        static WikidataProfile empty() {
            return new WikidataProfile("", "");
        }
    }

    private record WikiSummary(String introduction, String sourceUrl, String imageName) {
        static WikiSummary empty() {
            return new WikiSummary("", "", null);
        }
    }

    private record CommonsImage(String imageUrl, String sourceUrl) {
        static CommonsImage empty() {
            return new CommonsImage("", "");
        }
    }
}
