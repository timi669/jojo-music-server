package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.mapper.SongMapper;
import cn.edu.seig.jojomusic.model.entity.Song;
import cn.edu.seig.jojomusic.model.vo.LyricImportPreviewItem;
import cn.edu.seig.jojomusic.model.vo.LyricSongCandidate;
import cn.edu.seig.jojomusic.service.MinioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LyricImportServiceTest {

    private SongMapper songMapper;
    private MinioService minioService;
    private LyricImportService service;

    @BeforeEach
    void setUp() {
        songMapper = mock(SongMapper.class);
        minioService = mock(MinioService.class);
        service = new LyricImportService(songMapper, minioService, new ObjectMapper());
    }

    @Test
    void previewsUnicodeLrcAndMatchesTitleAndArtist() {
        LyricSongCandidate candidate = candidate("豆科鱼", "艺人", null);
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        MockMultipartFile file = lrcFile("艺人 - 豆科鱼.lrc", "[00:02.5]歌词\n[00:01:23]另一行");

        List<LyricImportPreviewItem> result = service.preview(new MockMultipartFile[]{file});

        assertEquals(1, result.size());
        assertEquals("EXACT_MATCH", result.get(0).getMatchStatus());
        assertEquals(2, result.get(0).getTimestampCount());
        assertEquals(7L, result.get(0).getSuggestedSongId());
    }

    @Test
    void matchesEveryCommaSeparatedCollaboratingArtistBeforeNormalizingPunctuation() {
        LyricSongCandidate candidate = candidate(
                "Smokin Out The Window",
                "Bruno Mars",
                null);
        candidate.setArtistCredits("Bruno Mars|Anderson .Paak|Silk Sonic");
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        MockMultipartFile file = lrcFile(
                "Bruno Mars,Anderson .Paak,Silk Sonic - Smokin Out The Window.lrc",
                "[00:01.00]Lyrics");

        List<LyricImportPreviewItem> result = service.preview(new MockMultipartFile[]{file});

        assertEquals("EXACT_MATCH", result.get(0).getMatchStatus());
        assertEquals(7L, result.get(0).getSuggestedSongId());
    }

    @Test
    void doesNotMatchWhenOneCollaboratingArtistIsMissingFromSongCredits() {
        LyricSongCandidate candidate = candidate("Smokin Out The Window", "Bruno Mars", null);
        candidate.setArtistCredits("Bruno Mars|Anderson .Paak");
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        MockMultipartFile file = lrcFile(
                "Bruno Mars,Anderson .Paak,Silk Sonic - Smokin Out The Window.lrc",
                "[00:01.00]Lyrics");

        List<LyricImportPreviewItem> result = service.preview(new MockMultipartFile[]{file});

        assertEquals("TITLE_MATCH_REVIEW_ARTIST", result.get(0).getMatchStatus());
        assertEquals(1, result.get(0).getCandidates().size());
    }

    @Test
    void rejectsLrcWithoutValidTimedLyricText() {
        MockMultipartFile file = lrcFile("艺人 - 豆科鱼.lrc", "[00:60.00]歌词");

        assertThrows(IllegalArgumentException.class,
                () -> service.preview(new MockMultipartFile[]{file}));
    }

    @Test
    void uploadsToLyricsFolderAndUpdatesOnlyTheSelectedSong() throws Exception {
        LyricSongCandidate candidate = candidate("豆科鱼", "艺人", null);
        Song song = new Song().setSongId(7L).setSongName("豆科鱼");
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        when(songMapper.selectById(7L)).thenReturn(song);
        when(minioService.uploadFile(any(), eq("lyrics"), eq("text/plain; charset=utf-8")))
                .thenReturn("https://minio.example/music/lyrics/new.lrc");
        when(songMapper.updateLyricIfUnchanged(
                7L, null, "https://minio.example/music/lyrics/new.lrc")).thenReturn(1);
        MockMultipartFile file = lrcFile("艺人 - 豆科鱼.lrc", "[00:01.00]歌词");
        String mappings = """
                [{"fileIndex":0,"songId":7,"replaceExisting":false,"expectedLyric":null}]
                """;

        int imported = service.importSelected(new MockMultipartFile[]{file}, mappings);

        assertEquals(1, imported);
        verify(minioService).uploadFile(any(), eq("lyrics"), eq("text/plain; charset=utf-8"));
        verify(songMapper).updateLyricIfUnchanged(
                7L, null, "https://minio.example/music/lyrics/new.lrc");
    }

    @Test
    void refusesToReplaceExistingLyricsWithoutExplicitApproval() {
        String existingLyric = "https://minio.example/music/lyrics/old.lrc";
        LyricSongCandidate candidate = candidate("豆科鱼", "艺人", existingLyric);
        Song song = new Song().setSongId(7L).setSongName("豆科鱼").setLyric(existingLyric);
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        when(songMapper.selectById(7L)).thenReturn(song);
        MockMultipartFile file = lrcFile("艺人 - 豆科鱼.lrc", "[00:01.00]歌词");
        String mappings = """
                [{"fileIndex":0,"songId":7,"replaceExisting":false,"expectedLyric":"https://minio.example/music/lyrics/old.lrc"}]
                """;

        assertThrows(IllegalArgumentException.class,
                () -> service.importSelected(new MockMultipartFile[]{file}, mappings));
        verify(minioService, never()).uploadFile(any(), eq("lyrics"), eq("text/plain; charset=utf-8"));
    }

    @Test
    void refusesWhenTheLyricChangedSincePreview() {
        String oldLyric = "https://minio.example/music/lyrics/old.lrc";
        LyricSongCandidate candidate = candidate(
                "豆科鱼", "艺人", "https://minio.example/music/lyrics/updated.lrc");
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        MockMultipartFile file = lrcFile("艺人 - 豆科鱼.lrc", "[00:01.00]歌词");
        String mappings = """
                [{"fileIndex":0,"songId":7,"replaceExisting":true,"expectedLyric":"%s"}]
                """.formatted(oldLyric);

        assertThrows(IllegalArgumentException.class,
                () -> service.importSelected(new MockMultipartFile[]{file}, mappings));
        verify(minioService, never()).uploadFile(any(), eq("lyrics"), eq("text/plain; charset=utf-8"));
    }

    @Test
    void acceptsAnExplicitManualSongMatchFromTheCatalog() throws Exception {
        LyricSongCandidate candidate = candidate("不同的歌名", "艺人", null);
        Song song = new Song().setSongId(7L).setSongName("不同的歌名");
        when(songMapper.selectLyricImportCandidates(null, null)).thenReturn(List.of(candidate));
        when(songMapper.selectById(7L)).thenReturn(song);
        when(minioService.uploadFile(any(), eq("lyrics"), eq("text/plain; charset=utf-8")))
                .thenReturn("https://minio.example/music/lyrics/manual.lrc");
        when(songMapper.updateLyricIfUnchanged(
                7L, null, "https://minio.example/music/lyrics/manual.lrc")).thenReturn(1);
        MockMultipartFile file = lrcFile("艺人 - 原文件名.lrc", "[00:01.00]歌词");
        String mappings = """
                [{"fileIndex":0,"songId":7,"replaceExisting":false,"manualMatch":true,"expectedLyric":null}]
                """;

        assertEquals(1, service.importSelected(new MockMultipartFile[]{file}, mappings));
        verify(songMapper).updateLyricIfUnchanged(
                7L, null, "https://minio.example/music/lyrics/manual.lrc");
    }

    private LyricSongCandidate candidate(String title, String artist, String lyricUrl) {
        LyricSongCandidate candidate = new LyricSongCandidate();
        candidate.setSongId(7L);
        candidate.setSongName(title);
        candidate.setArtistName(artist);
        candidate.setArtistCredits(artist);
        candidate.setLyricUrl(lyricUrl);
        candidate.setHasLyric(lyricUrl != null && !lyricUrl.isBlank());
        return candidate;
    }

    private MockMultipartFile lrcFile(String filename, String content) {
        return new MockMultipartFile(
                "files", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
