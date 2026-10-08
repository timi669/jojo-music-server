package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * Mapper 接口
 * </p>
 *
 * @author timi669
 * @since 2025-01-09
 */
@Mapper
public interface SongMapper extends BaseMapper<Song> {

    // 获取歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.name AS songName, 
                    s.album, 
                    s.duration, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                WHERE 
                    (#{keyword} IS NULL OR TRIM(#{keyword}) = ''
                        OR s.name LIKE CONCAT('%', #{keyword}, '%')
                        OR a.name LIKE CONCAT('%', #{keyword}, '%')
                        OR EXISTS (
                            SELECT 1
                            FROM tb_song_artist sa
                            LEFT JOIN tb_artist sa_artist ON sa.artist_id = sa_artist.id
                            WHERE sa.song_id = s.id
                              AND sa_artist.name LIKE CONCAT('%', #{keyword}, '%')
                        )
                        OR s.album LIKE CONCAT('%', #{keyword}, '%'))
                    AND (#{songName} IS NULL OR TRIM(#{songName}) = '' OR s.name LIKE CONCAT('%', #{songName}, '%'))
                    AND (
                        #{artistName} IS NULL OR TRIM(#{artistName}) = ''
                        OR a.name LIKE CONCAT('%', #{artistName}, '%')
                        OR EXISTS (
                            SELECT 1
                            FROM tb_song_artist sa
                            LEFT JOIN tb_artist sa_artist ON sa.artist_id = sa_artist.id
                            WHERE sa.song_id = s.id
                              AND sa_artist.name LIKE CONCAT('%', #{artistName}, '%')
                        )
                    )
                    AND (#{album} IS NULL OR TRIM(#{album}) = '' OR s.album LIKE CONCAT('%', #{album}, '%'))
            """)
    IPage<SongVO> getSongsWithArtist(Page<SongVO> page,
                                     @Param("keyword") String keyword,
                                     @Param("songName") String songName,
                                     @Param("artistName") String artistName,
                                     @Param("album") String album);

    // 获取歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.name AS songName, 
                    s.artist_id AS artistId, 
                    s.album, 
                    s.lyric, 
                    s.duration, 
                    s.style, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                WHERE 
                    (#{artistId} IS NULL
                        OR s.artist_id = #{artistId}
                        OR EXISTS (
                            SELECT 1
                            FROM tb_song_artist sa
                            WHERE sa.song_id = s.id
                              AND sa.artist_id = #{artistId}
                        ))
                    AND (#{songName} IS NULL OR s.name LIKE CONCAT('%', #{songName}, '%'))
                    AND (#{album} IS NULL OR s.album LIKE CONCAT('%', #{album}, '%'))
                ORDER BY s.release_time DESC
            """)
    IPage<SongAdminVO> getSongsWithArtistName(Page<SongAdminVO> page,
                                              @Param("artistId") Long artistId,
                                              @Param("songName") String songName,
                                              @Param("album") String album);

    // 获取随机歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.name AS songName, 
                    s.album, 
                    s.duration, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                ORDER BY RAND() LIMIT 20
            """)
    List<SongVO> getRandomSongsWithArtist();

    // 根据id获取歌曲详情
    SongDetailVO getSongDetailById(Long songId);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    IPage<SongVO> getSongsByIds(Page<SongVO> page,
                                @Param("songIds") List<Long> songIds,
                                @Param("songName") String songName,
                                @Param("artistName") String artistName,
                                @Param("album") String album);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    List<Long> getFavoriteSongStyles(@Param("favoriteSongIds") List<Long> favoriteSongIds);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    List<SongVO> getRecommendedSongsByStyles(@Param("sortedStyleIds") List<Long> sortedStyleIds,
                                             @Param("favoriteSongIds") List<Long> favoriteSongIds,
                                             @Param("limit") int limit);

}
