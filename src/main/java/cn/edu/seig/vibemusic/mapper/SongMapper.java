package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.model.vo.LyricSongCandidate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(credits.artistCredits, a.name) AS artistCredits,
                   s.lyric AS lyricUrl,
                   (s.lyric IS NOT NULL AND TRIM(s.lyric) &lt;&gt; '') AS hasLyric
            FROM tb_song s
            LEFT JOIN tb_artist a ON a.id = s.artist_id
            LEFT JOIN (
                SELECT sa.song_id,
                       GROUP_CONCAT(credit.name ORDER BY sa.credit_order SEPARATOR '|') AS artistCredits
                FROM tb_song_artist sa
                JOIN tb_artist credit ON credit.id = sa.artist_id
                GROUP BY sa.song_id
            ) credits ON credits.song_id = s.id
            <if test="keyword != null and keyword != ''">
                WHERE s.name LIKE CONCAT('%', #{keyword}, '%')
                   OR a.name LIKE CONCAT('%', #{keyword}, '%')
                   OR EXISTS (
                       SELECT 1
                       FROM tb_song_artist sa
                       JOIN tb_artist credit ON credit.id = sa.artist_id
                       WHERE sa.song_id = s.id
                         AND credit.name LIKE CONCAT('%', #{keyword}, '%')
                   )
            </if>
            ORDER BY s.id
            <if test="limit != null">
                LIMIT #{limit}
            </if>
            </script>
            """)
    List<LyricSongCandidate> selectLyricImportCandidates(
            @Param("keyword") String keyword,
            @Param("limit") Integer limit);

    /**
     * 只取尚无歌词的歌曲，供自动抓取使用。
     *
     * <p>用 {@code lyric IS NULL OR TRIM(lyric) = ''} 作为条件，本身就是"已有的不动"的
     * 第一道保障：已有歌词的歌曲根本不会进入候选集。
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(credits.artistCredits, a.name) AS artistCredits,
                   s.lyric AS lyricUrl,
                   FALSE AS hasLyric
            FROM tb_song s
            LEFT JOIN tb_artist a ON a.id = s.artist_id
            LEFT JOIN (
                SELECT sa.song_id,
                       GROUP_CONCAT(credit.name ORDER BY sa.credit_order SEPARATOR '|') AS artistCredits
                FROM tb_song_artist sa
                JOIN tb_artist credit ON credit.id = sa.artist_id
                GROUP BY sa.song_id
            ) credits ON credits.song_id = s.id
            WHERE s.lyric IS NULL OR TRIM(s.lyric) = ''
            ORDER BY s.id
            <if test="limit != null">
                LIMIT #{limit}
            </if>
            </script>
            """)
    List<LyricSongCandidate> selectSongsMissingLyric(@Param("limit") Integer limit);

    @Update("""
            UPDATE tb_song
            SET lyric = #{lyricUrl}
            WHERE id = #{songId}
              AND ((#{expectedLyric} IS NULL AND (lyric IS NULL OR TRIM(lyric) = ''))
                   OR lyric = #{expectedLyric})
            """)
    int updateLyricIfUnchanged(
            @Param("songId") Long songId,
            @Param("expectedLyric") String expectedLyric,
            @Param("lyricUrl") String lyricUrl);

    @Select("""
            SELECT id AS songId, artist_id AS artistId, audio_url AS audioUrl
            FROM tb_song
            ORDER BY id ASC
            """)
    List<Song> selectAudioArtistScanTargets();

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
