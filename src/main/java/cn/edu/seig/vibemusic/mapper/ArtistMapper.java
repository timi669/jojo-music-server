package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

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
public interface ArtistMapper extends BaseMapper<Artist> {

    // 根据id查询歌手详情
    ArtistDetailVO getArtistDetailById(Long artistId);

    // 查询歌手作为主艺人或合作艺人参与的歌曲
    List<SongVO> getSongsByArtistId(Long artistId);

}
