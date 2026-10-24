package cn.edu.seig.jojomusic.mapper;

import cn.edu.seig.jojomusic.model.entity.SongArtist;
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

    /**
     * 批量查询多首歌曲的艺人署名。
     *
     * <p>注意：MyBatis 注解 SQL 中若要使用动态标签（{@code <foreach>} 等），
     * 必须用 {@code <script>} 包裹整段 —— 原实现直写 {@code <foreach>} 而无包裹，
     * 一旦调用即抛 BuilderException（该异常只在运行时暴露，编译期无任何提示）。
     *
     * <p>返回所有歌曲的艺人名扁平列表（按 song_id / credit_order 排序），
     * 调用方按 song_id 分组使用。当前无调用方，属备用方法。
     */
    @Select("""
            <script>
            SELECT a.name
            FROM tb_song_artist sa
            LEFT JOIN tb_artist a ON sa.artist_id = a.id
            WHERE sa.song_id IN
            <foreach collection="songIds" item="songId" open="(" separator="," close=")">
                #{songId}
            </foreach>
            ORDER BY sa.song_id ASC, sa.credit_order ASC, sa.artist_id ASC
            </script>
            """)
    List<String> selectArtistNamesBySongIds(@Param("songIds") List<Long> songIds);
}
