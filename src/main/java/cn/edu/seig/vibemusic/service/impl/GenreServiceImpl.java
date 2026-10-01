package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.model.entity.Genre;
import cn.edu.seig.vibemusic.mapper.GenreMapper;
import cn.edu.seig.vibemusic.service.IGenreService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
public class GenreServiceImpl extends ServiceImpl<GenreMapper, Genre> implements IGenreService {

}
