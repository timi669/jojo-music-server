package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.SongArtist;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.MinioService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * deleteArtist / deleteArtists 的安全护栏回归测试。
 *
 * <p>背景：tb_song.artist_id 外键为 ON DELETE CASCADE，直接删除歌手会连带删除
 * 其名下全部歌曲，且这些歌曲在 MinIO 的音频/封面不会被清理，造成数据静默丢失。
 * 因此删除前必须先校验"名下是否仍有歌曲"，有则拒绝。
 */
class ArtistServiceImplDeleteGuardTest {

    private ArtistMapper artistMapper;
    private SongMapper songMapper;
    private SongArtistMapper songArtistMapper;
    private MinioService minioService;
    private ArtistServiceImpl service;

    @BeforeEach
    void setUp() {
        artistMapper = mock(ArtistMapper.class);
        songMapper = mock(SongMapper.class);
        songArtistMapper = mock(SongArtistMapper.class);
        minioService = mock(MinioService.class);
        service = new ArtistServiceImpl();
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "songMapper", songMapper);
        ReflectionTestUtils.setField(service, "songArtistMapper", songArtistMapper);
        ReflectionTestUtils.setField(service, "minioService", minioService);
    }

    @Test
    void refusesToDeleteArtistThatStillOwnsSongs() {
        when(artistMapper.selectById(1L)).thenReturn(new Artist().setArtistName("Eminem"));
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(53L);

        Result result = service.deleteArtist(1L);

        assertEquals(1, result.getCode(), "应返回失败");
        assertTrue(result.getMessage().contains("53"), "应提示名下歌曲数量");
        // 关键：绝不能落到删除
        verify(artistMapper, never()).deleteById(any());
        verify(minioService, never()).deleteFile(any());
    }

    @Test
    void deletesArtistWithNoSongsAndCleansRelationsAndAvatar() {
        String avatar = "http://localhost:9000/vibe-music-personal/artists/x.jpg";
        when(artistMapper.selectById(2L)).thenReturn(new Artist().setArtistName("Far East Movement").setAvatar(avatar));
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(artistMapper.deleteById(2L)).thenReturn(1);

        Result result = service.deleteArtist(2L);

        assertEquals(0, result.getCode(), "应删除成功");
        // 先清理合作艺人关联，再删歌手
        verify(songArtistMapper).delete(any(QueryWrapper.class));
        verify(minioService).deleteFile(avatar);
        verify(artistMapper).deleteById(2L);
    }

    @Test
    void reportsNotFoundForUnknownArtist() {
        when(artistMapper.selectById(3L)).thenReturn(null);

        Result result = service.deleteArtist(3L);

        assertEquals(1, result.getCode());
        verify(artistMapper, never()).deleteById(any());
        verify(songMapper, never()).selectCount(any());
    }

    @Test
    void batchDeleteRefusesWhenAnyArtistStillOwnsSongs() {
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(7L);

        Result result = service.deleteArtists(List.of(1L, 2L));

        assertEquals(1, result.getCode(), "整批应被拒绝");
        assertTrue(result.getMessage().contains("7"));
        verify(artistMapper, never()).deleteByIds(any());
        verify(songArtistMapper, never()).delete(any(QueryWrapper.class));
    }

    @Test
    void batchDeleteSucceedsWhenNoneOwnSongs() {
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(artistMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                new Artist().setArtistName("A"),
                new Artist().setArtistName("B")));
        when(artistMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        Result result = service.deleteArtists(List.of(1L, 2L));

        assertEquals(0, result.getCode());
        verify(songArtistMapper).delete(any(QueryWrapper.class));
        verify(artistMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void batchDeleteRejectsEmptyList() {
        Result result = service.deleteArtists(List.of());

        assertEquals(1, result.getCode());
        assertNotNull(result.getMessage());
        verify(artistMapper, never()).deleteByIds(any());
    }

    @Test
    void songOwnershipCheckTargetsArtistIdColumn() {
        when(artistMapper.selectById(9L)).thenReturn(new Artist().setArtistName("X"));
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        service.deleteArtist(9L);

        ArgumentCaptor<QueryWrapper<Song>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(songMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("artist_id"), "应校验 artist_id 归属");
    }

    @Test
    void relationCleanupTargetsArtistIdColumn() {
        when(artistMapper.selectById(10L)).thenReturn(new Artist().setArtistName("Y"));
        when(songMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(artistMapper.deleteById(10L)).thenReturn(1);

        service.deleteArtist(10L);

        ArgumentCaptor<QueryWrapper<SongArtist>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(songArtistMapper).delete(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("artist_id"));
    }
}