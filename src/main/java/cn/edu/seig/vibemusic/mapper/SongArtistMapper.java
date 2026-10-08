package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.SongArtist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SongArtistMapper extends BaseMapper<SongArtist> {

    @Select("""
            SELECT a.name
            FROM tb_song_artist sa
            LEFT JOIN tb_artist a ON sa.artist_id = a.id
            WHERE sa.song_id = #{songId}
            ORDER BY sa.credit_order ASC, sa.artist_id ASC
            """)
    List<String> selectArtistNamesBySongId(@Param("songId") Long songId);

    @Select("""
            SELECT a.name
            FROM tb_song_artist sa
            LEFT JOIN tb_artist a ON sa.artist_id = a.id
            WHERE sa.song_id IN
            <foreach collection="songIds" item="songId" open="(" separator="," close=")">
                #{songId}
            </foreach>
            ORDER BY sa.song_id ASC, sa.credit_order ASC, sa.artist_id ASC
            """)
    List<String> selectArtistNamesBySongIds(@Param("songIds") List<Long> songIds);
}
