package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.mapper.SongMapper;
import cn.edu.seig.jojomusic.model.dto.LyricImportMapping;
import cn.edu.seig.jojomusic.model.entity.Song;
import cn.edu.seig.jojomusic.model.vo.LyricImportPreviewItem;
import cn.edu.seig.jojomusic.model.vo.LyricSongCandidate;
import cn.edu.seig.jojomusic.service.MinioService;
import cn.edu.seig.jojomusic.util.ByteArrayMultipartFile;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LyricImportService {

    private static final Logger log = LoggerFactory.getLogger(LyricImportService.class);
    private static final int MAX_FILE_COUNT = 1000;
    private static final int MAX_FILE_SIZE_BYTES = 1024 * 1024;
    private static final Pattern LRC_TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Set<String> LRC_METADATA = Set.of("ar", "al", "ti", "by", "re", "ve", "offset");

    private final SongMapper songMapper;
    private final MinioService minioService;
    private final ObjectMapper objectMapper;

    public LyricImportService(SongMapper songMapper, MinioService minioService, ObjectMapper objectMapper) {
        this.songMapper = songMapper;
        this.minioService = minioService;
        this.objectMapper = objectMapper;
    }

    public List<LyricImportPreviewItem> preview(MultipartFile[] files) {
        validateFileCount(files);
        List<LyricSongCandidate> songs = songMapper.selectLyricImportCandidates(null, null);
        List<LyricImportPreviewItem> preview = new ArrayList<>(files.length);
        Set<String> filenames = new HashSet<>();

        for (int fileIndex = 0; fileIndex < files.length; fileIndex++) {
            MultipartFile file = files[fileIndex];
            ValidatedLrc lrc = validateLrc(file);
            String filename = safeFilename(file.getOriginalFilename());
            if (!filenames.add(filename.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("所选 LRC 文件中有重名文件：" + filename);
            }
            preview.add(match(fileIndex, filename, file.getSize(), lrc, songs));
        }
        return preview;
    }

    public List<LyricSongCandidate> searchSongs(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("请输入歌曲名或歌手名。");
        }
        if (keyword.length() > 100) {
            throw new IllegalArgumentException("搜索内容不能超过 100 个字符。");
        }
        return songMapper.selectLyricImportCandidates(keyword.trim(), 100);
    }

    @Transactional
    @CacheEvict(cacheNames = {"songCache", "artistCache"}, allEntries = true)
    public int importSelected(MultipartFile[] files, String mappingsJson) {
        validateFileCount(files);
        List<LyricImportMapping> mappings;
        try {
            mappings = objectMapper.readValue(mappingsJson, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("歌词导入映射数据无效。", exception);
        }
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一首歌曲进行歌词导入。");
        }

        List<LyricSongCandidate> songs = songMapper.selectLyricImportCandidates(null, null);
        Set<Integer> fileIndexes = new HashSet<>();
        Set<Long> songIds = new HashSet<>();
        List<ImportItem> items = new ArrayList<>(mappings.size());
        for (LyricImportMapping mapping : mappings) {
            if (mapping == null || mapping.getFileIndex() == null) {
                throw new IllegalArgumentException("歌词文件映射数据缺少文件序号。");
            }
            int fileIndex = mapping.getFileIndex();
            if (fileIndex < 0 || fileIndex >= files.length || !fileIndexes.add(fileIndex)) {
                throw new IllegalArgumentException("歌词文件映射重复或索引无效。");
            }
            if (mapping.getSongId() == null || !songIds.add(mapping.getSongId())) {
                throw new IllegalArgumentException("同一首歌曲不能在一次批量导入中重复指定歌词。");
            }

            MultipartFile file = files[fileIndex];
            ValidatedLrc lrc = validateLrc(file);
            String filename = safeFilename(file.getOriginalFilename());
            LyricImportPreviewItem match = match(fileIndex, filename, file.getSize(), lrc, songs);
            boolean titleMatched = match.getCandidates().stream().anyMatch(candidate ->
                    candidate.getSongId().equals(mapping.getSongId()));
            boolean existsInCatalog = songs.stream().anyMatch(candidate ->
                    candidate.getSongId().equals(mapping.getSongId()));
            if (!titleMatched && (!mapping.isManualMatch() || !existsInCatalog)) {
                throw new IllegalArgumentException(
                        "所选歌曲未通过匹配校验：" + filename + "。请使用预览候选或人工搜索选择歌曲。");
            }
            LyricSongCandidate selectedCandidate = songs.stream()
                    .filter(candidate -> candidate.getSongId().equals(mapping.getSongId()))
                    .findFirst()
                    .orElseThrow();
            if (!Objects.equals(selectedCandidate.getLyricUrl(), mapping.getExpectedLyric())) {
                throw new IllegalArgumentException(
                        "歌曲歌词在预览后发生变化，请重新预览并审核：" + filename);
            }

            Song song = songMapper.selectById(mapping.getSongId());
            if (song == null) {
                throw new IllegalArgumentException("歌曲不存在，编号：" + mapping.getSongId());
            }
            String currentLyric = song.getLyric();
            if (!Objects.equals(currentLyric, mapping.getExpectedLyric())) {
                throw new IllegalArgumentException(
                        "歌曲歌词在导入期间发生变化，请重新预览并审核：" + filename);
            }
            boolean hasExistingLyric = currentLyric != null && !currentLyric.isBlank();
            if (hasExistingLyric && !mapping.isReplaceExisting()) {
                throw new IllegalArgumentException(
                        "歌曲已有歌词；如确认覆盖，请在导入预览中明确勾选覆盖：" + song.getSongName());
            }
            items.add(new ImportItem(file, filename, lrc.content(), song, mapping.getExpectedLyric()));
        }

        List<String> uploadedUrls = new ArrayList<>(items.size());
        try {
            for (ImportItem item : items) {
                String safeObjectName = UUID.randomUUID() + ".lrc";
                ByteArrayMultipartFile uploadFile = new ByteArrayMultipartFile(
                        "lyric",
                        safeObjectName,
                        "text/plain; charset=utf-8",
                        item.content());
                uploadedUrls.add(minioService.uploadFile(uploadFile, "lyrics", "text/plain; charset=utf-8"));
            }

            for (int index = 0; index < items.size(); index++) {
                ImportItem item = items.get(index);
                int updated = songMapper.updateLyricIfUnchanged(
                        item.song().getSongId(),
                        item.previousLyric(),
                        uploadedUrls.get(index));
                if (updated != 1) {
                    throw new IllegalStateException(
                            "歌曲歌词在导入期间发生变化，已取消本次导入：" + item.song().getSongName());
                }
            }
        } catch (RuntimeException exception) {
            for (String uploadedUrl : uploadedUrls) {
                try {
                    minioService.deleteFile(uploadedUrl);
                } catch (RuntimeException cleanupException) {
                    log.error("Failed to clean up an unreferenced lyric object after import failure", cleanupException);
                }
            }
            throw exception;
        }

        return items.size();
    }

    private LyricImportPreviewItem match(
            int fileIndex,
            String filename,
            long size,
            ValidatedLrc lrc,
            List<LyricSongCandidate> songs) {
        FilenameParts parts = parseFilename(filename);
        String titleKey = normalize(parts.title());
        String artistKey = normalize(parts.artist());
        List<LyricSongCandidate> titleMatches = songs.stream()
                .filter(song -> normalize(song.getSongName()).equals(titleKey))
                .toList();
        List<LyricSongCandidate> exactMatches = titleMatches.stream()
                .filter(song -> artistMatches(parts.artist(), song))
                .toList();

        String status;
        Long suggestedId = null;
        List<LyricSongCandidate> candidates;
        if (exactMatches.size() == 1) {
            status = "EXACT_MATCH";
            suggestedId = exactMatches.get(0).getSongId();
            candidates = exactMatches;
        } else if (!exactMatches.isEmpty()) {
            status = "AMBIGUOUS_MATCH";
            candidates = exactMatches;
        } else if (!titleMatches.isEmpty()) {
            status = "TITLE_MATCH_REVIEW_ARTIST";
            candidates = titleMatches;
        } else {
            status = "NO_MATCH";
            candidates = List.of();
        }

        boolean existingLyric = candidates.size() == 1 && candidates.get(0).isHasLyric();
        return new LyricImportPreviewItem(
                fileIndex,
                filename,
                size,
                lrc.timestampCount(),
                parts.title(),
                parts.artist(),
                status,
                suggestedId,
                existingLyric,
                candidates);
    }

    private boolean artistMatches(String artistName, LyricSongCandidate song) {
        if (artistName == null || artistName.isBlank()) {
            return false;
        }
        Set<String> fileArtists = new HashSet<>();
        for (String artist : artistName.split("[,，&、/]+")) {
            String normalized = normalize(artist);
            if (!normalized.isBlank()) {
                fileArtists.add(normalized);
            }
        }
        if (fileArtists.isEmpty()) {
            return false;
        }

        Set<String> credits = new HashSet<>();
        addNormalizedCredits(credits, song.getArtistName());
        addNormalizedCredits(credits, song.getArtistCredits());
        return fileArtists.stream().allMatch(credits::contains);
    }

    private void addNormalizedCredits(Set<String> credits, String names) {
        if (names == null || names.isBlank()) {
            return;
        }
        for (String name : names.split("[|,，&、/]+")) {
            String normalized = normalize(name);
            if (!normalized.isBlank()) {
                credits.add(normalized);
            }
        }
    }

    private FilenameParts parseFilename(String filename) {
        String nameWithoutExtension = filename.substring(0, filename.length() - 4);
        int separator = nameWithoutExtension.lastIndexOf(" - ");
        if (separator < 0) {
            return new FilenameParts("", nameWithoutExtension);
        }
        return new FilenameParts(
                nameWithoutExtension.substring(0, separator).trim(),
                nameWithoutExtension.substring(separator + 3).trim());
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private ValidatedLrc validateLrc(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("歌词文件不能为空。");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("LRC 文件不能超过 1 MB：" + safeFilename(file.getOriginalFilename()));
        }

        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
            throw new IllegalArgumentException("仅支持 .lrc 歌词文件：" + filename);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 LRC 文件失败：" + filename, exception);
        }
        String content = decodeText(bytes, filename).replaceFirst("^\\uFEFF", "");
        Matcher matcher = LRC_TIMESTAMP.matcher(content);
        int timestampCount = 0;
        boolean hasLyricText = false;
        while (matcher.find()) {
            int seconds = Integer.parseInt(matcher.group(2));
            if (seconds > 59) {
                throw new IllegalArgumentException("LRC 时间戳中的秒数无效：" + filename);
            }
            timestampCount++;
            int lineEnd = content.indexOf('\n', matcher.end());
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            if (!content.substring(matcher.end(), lineEnd).replace("\r", "").isBlank()) {
                hasLyricText = true;
            }
        }
        if (timestampCount == 0 || !hasLyricText) {
            throw new IllegalArgumentException("文件中没有有效的带时间轴歌词：" + filename);
        }
        return new ValidatedLrc(content.getBytes(StandardCharsets.UTF_8), timestampCount);
    }

    private String decodeText(byte[] bytes, String filename) {
        try {
            if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
                return StandardCharsets.UTF_16LE.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2))
                        .toString();
            }
            if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
                return StandardCharsets.UTF_16BE.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2))
                        .toString();
            }
            int offset = bytes.length >= 3
                    && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf
                    ? 3 : 0;
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("歌词文件编码无法识别，请使用 UTF-8 或带 BOM 的 UTF-16：" + filename, exception);
        }
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("歌词文件缺少文件名。");
        }
        String safe = filename.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new IllegalArgumentException("歌词文件名无效。");
        }
        return safe;
    }

    private void validateFileCount(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请至少选择一个 LRC 文件。");
        }
        if (files.length > MAX_FILE_COUNT) {
            throw new IllegalArgumentException("一次最多处理 1000 个 LRC 文件。");
        }
    }

    private record FilenameParts(String artist, String title) {
    }

    private record ValidatedLrc(byte[] content, int timestampCount) {
    }

    private record ImportItem(
            MultipartFile file,
            String filename,
            byte[] content,
            Song song,
            String previousLyric) {
    }
}
