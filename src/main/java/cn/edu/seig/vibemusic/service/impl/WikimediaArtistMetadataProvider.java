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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class WikimediaArtistMetadataProvider implements ArtistMetadataProvider {

    private static final int MINIMUM_MUSICBRAINZ_SCORE = 90;
    private static final int MUSICBRAINZ_RESULT_LIMIT = 100;
    // This app is primarily used in Chinese, so prefer Chinese biographies when available
    // and only fall back to other languages if the Chinese article is missing.
    private static final List<String> PREFERRED_WIKIPEDIA_LANGUAGES =
            List.of("zh", "en", "ja", "ko", "fr", "de", "es", "pt", "it");
    private static final Pattern WIKIDATA_ID = Pattern.compile("Q[1-9][0-9]*");
    private static final Pattern MUSIC_DESCRIPTION = Pattern.compile(
            "\\b(singer|songwriter|musician|rapper|composer|producer|dj|musical artist|music group|band|"
                    + "vocal group|orchestra|choir)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GROUP_DESCRIPTION = Pattern.compile(
            "\\b(group|band|orchestra|choir|duo|trio|quartet)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final String USER_AGENT =
            "JOJO-MUSIC/1.0 (https://github.com/timi669/jojo-music-server; artist profile enrichment)";
    private static final Logger log = LoggerFactory.getLogger(WikimediaArtistMetadataProvider.class);

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
        JsonNode artistDetails = null;
        try {
            JsonNode searchResults = musicBrainzSearch(artistName);
            if (hasUniqueExactMatch(artistName, searchResults)) {
                for (JsonNode candidate : searchResults.path("artists")) {
                    if (normalize(candidate.path("name").asText()).equals(normalize(artistName))) {
                        String artistId = candidate.path("id").asText();
                        if (!artistId.isBlank()) {
                            artistDetails = musicBrainzArtistDetails(artistId);
                        }
                        break;
                    }
                }
            }
        } catch (IOException exception) {
            log.warn("MusicBrainz lookup failed for '{}'; trying exact Wikidata matching: {}",
                    artistName, exception.getMessage());
        }

        String wikidataId = artistDetails == null ? "" : findWikidataId(artistDetails);
        Optional<WikidataArtistMatch> wikidataMatch = Optional.empty();
        if (wikidataId.isBlank()) {
            wikidataMatch = wikidataArtistSearch(artistName);
            wikidataId = wikidataMatch.map(WikidataArtistMatch::id).orElse("");
        }
        if (wikidataId.isBlank() && artistDetails == null) {
            return Optional.empty();
        }

        WikidataProfile wikidataProfile = wikidataId.isBlank()
                ? WikidataProfile.empty()
                : wikidataProfile(wikidataId);

        String wikipediaUrl = artistDetails == null ? "" : findWikipediaUrl(artistDetails);
        if (wikipediaUrl.isBlank()) {
            wikipediaUrl = wikidataProfile.wikipediaUrl();
        }
        WikiSummary summary = wikipediaUrl.isBlank() ? WikiSummary.empty() : wikipediaSummary(wikipediaUrl);
        String imageName = wikidataProfile.imageName();
        if (imageName.isBlank() && summary.imageName() != null) {
            imageName = summary.imageName();
        }
        CommonsImage commonsImage = imageName.isBlank() ? CommonsImage.empty() : commonsImage(imageName);
        Integer gender = artistDetails != null && "Group".equalsIgnoreCase(artistDetails.path("type").asText())
                || wikidataMatch.map(WikidataArtistMatch::group).orElse(false)
                ? 2
                : wikidataProfile.gender();
        if (gender == null && artistDetails != null) {
            gender = switch (artistDetails.path("gender").asText("").toLowerCase(java.util.Locale.ROOT)) {
                case "male" -> 0;
                case "female" -> 1;
                default -> null;
            };
        }

        if (summary.introduction().isBlank()
                && commonsImage.imageUrl().isBlank()
                && gender == null
                && wikidataProfile.area().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ArtistMetadata(
                commonsImage.imageUrl(),
                summary.introduction(),
                gender,
                wikidataProfile.area()
        ));
    }

    private Optional<WikidataArtistMatch> wikidataArtistSearch(String artistName)
            throws IOException, InterruptedException {
        URI uri = URI.create("https://www.wikidata.org/w/api.php?action=wbsearchentities&search="
                + encode(artistName)
                + "&language=en&uselang=en&format=json&limit=10");
        JsonNode results = getJson(uri, false).path("search");
        return selectExactWikidataArtist(artistName, results);
    }

    static Optional<WikidataArtistMatch> selectExactWikidataArtist(String artistName, JsonNode candidates) {
        Map<String, WikidataArtistMatch> matches = new LinkedHashMap<>();
        for (JsonNode candidate : candidates) {
            String id = candidate.path("id").asText("");
            String label = candidate.path("label").asText("");
            String description = candidate.path("description").asText("");
            boolean exactName = normalize(label).equals(normalize(artistName));
            for (JsonNode alias : candidate.path("aliases")) {
                String aliasName = alias.isTextual() ? alias.asText() : alias.path("value").asText("");
                exactName |= normalize(aliasName).equals(normalize(artistName));
            }
            if (WIKIDATA_ID.matcher(id).matches()
                    && exactName
                    && MUSIC_DESCRIPTION.matcher(description).find()) {
                matches.putIfAbsent(id, new WikidataArtistMatch(
                        id,
                        GROUP_DESCRIPTION.matcher(description).find()
                ));
            }
        }
        return matches.size() == 1 ? Optional.of(matches.values().iterator().next()) : Optional.empty();
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
        String genderId = firstClaimEntityId(entity, "P21");
        Integer gender = switch (genderId) {
            case "Q6581097" -> 0;
            case "Q6581072" -> 1;
            default -> null;
        };
        String countryId = firstClaimEntityId(entity, "P27");
        String area = countryId.isBlank() ? "" : wikidataLabel(countryId);
        return new WikidataProfile(imageName, wikipediaUrl, gender, area);
    }

    private String firstClaimEntityId(JsonNode entity, String property) {
        for (JsonNode claim : entity.path("claims").path(property)) {
            String id = claim.path("mainsnak").path("datavalue").path("value").path("id").asText("");
            if (WIKIDATA_ID.matcher(id).matches()) {
                return id;
            }
        }
        return "";
    }

    private String wikidataLabel(String entityId) throws IOException, InterruptedException {
        JsonNode response = getJson(URI.create("https://www.wikidata.org/w/api.php?action=wbgetentities"
                + "&ids=" + entityId + "&props=labels&languages=zh%7Cen&format=json"), false);
        JsonNode labels = response.path("entities").path(entityId).path("labels");
        String[] preferredLanguages = {"zh", "zh-hans", "zh-cn", "en"};
        for (String language : preferredLanguages) {
            String label = labels.path(language).path("value").asText("").trim();
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
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
        String extract = response.path("extract").asText("").trim();
        String thumbnail = response.path("thumbnail").path("source").asText("");
        if (thumbnail.isBlank()) {
            thumbnail = response.path("originalimage").path("source").asText("");
        }
        String imageName = commonsFileNameFromUploadUrl(thumbnail);
        return new WikiSummary(extract, imageName);
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
        URI imageUri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(imageUri.getScheme())
                || !isTrustedWikimediaImageHost(imageUri.getHost())) {
            return CommonsImage.empty();
        }
        return new CommonsImage(imageUrl);
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

    private record WikidataProfile(String imageName, String wikipediaUrl, Integer gender, String area) {
        static WikidataProfile empty() {
            return new WikidataProfile("", "", null, "");
        }
    }

    record WikidataArtistMatch(String id, boolean group) {
    }

    private record WikiSummary(String introduction, String imageName) {
        static WikiSummary empty() {
            return new WikiSummary("", null);
        }
    }

    private record CommonsImage(String imageUrl) {
        static CommonsImage empty() {
            return new CommonsImage("");
        }
    }
}
