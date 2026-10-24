package cn.edu.seig.jojomusic.service;

import cn.edu.seig.jojomusic.model.dto.ArtistAddDTO;
import cn.edu.seig.jojomusic.model.dto.ArtistDTO;
import cn.edu.seig.jojomusic.model.dto.ArtistUpdateDTO;
import cn.edu.seig.jojomusic.model.entity.Artist;
import cn.edu.seig.jojomusic.model.vo.ArtistDetailVO;
import cn.edu.seig.jojomusic.model.vo.ArtistNameVO;
import cn.edu.seig.jojomusic.model.vo.ArtistVO;
import cn.edu.seig.jojomusic.result.PageResult;
import cn.edu.seig.jojomusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author timi669
 * @since 2025-01-09
 */
public interface IArtistService extends IService<Artist> {

    // 获取所有歌手
    Result<PageResult<ArtistVO>> getAllArtists(ArtistDTO artistDTO);

    // 获取所有歌手
    Result<PageResult<Artist>> getAllArtistsAndDetail(ArtistDTO artistDTO);

    // 获取所有歌手id和名字
    Result<List<ArtistNameVO>> getAllArtistNames();

    // 获取随机歌手
    Result<List<ArtistVO>> getRandomArtists();

    // 根据id获取歌手详情
    Result<ArtistDetailVO> getArtistDetail(Long artistId, HttpServletRequest request);

    // 获取所有歌手数量
    Result<Long> getAllArtistsCount(Integer gender, String area);

    // 添加歌手
    Result addArtist(ArtistAddDTO artistAddDTO);

    // 更新歌手
    Result updateArtist(ArtistUpdateDTO artistUpdateDTO);

    // 更新歌手头像
    Result updateArtistAvatar(Long artistId, String avatar);

    // 删除歌手
    Result deleteArtist(Long ArtistId);

    // 批量删除歌手
    Result deleteArtists(List<Long> artistIds);

}
