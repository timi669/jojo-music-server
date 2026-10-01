package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.CommentMapper;
import cn.edu.seig.vibemusic.model.dto.CommentPlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.CommentSongDTO;
import cn.edu.seig.vibemusic.model.entity.Comment;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ICommentService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    @Autowired
    private CommentMapper commentMapper;

    /**
     * 添加歌曲评论
     *
     * @param commentSongDTO 歌曲评论DTO
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result addSongComment(CommentSongDTO commentSongDTO) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);

        Comment comment = new Comment();
        comment.setUserId(userId).setSongId(commentSongDTO.getSongId())
                .setContent(commentSongDTO.getContent()).setType(0)
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L);

        if (commentMapper.insert(comment) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 添加歌单评论
     *
     * @param commentPlaylistDTO 歌单评论DTO
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result addPlaylistComment(CommentPlaylistDTO commentPlaylistDTO) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);

        Comment comment = new Comment();
        comment.setUserId(userId).setPlaylistId(commentPlaylistDTO.getPlaylistId())
                .setContent(commentPlaylistDTO.getContent()).setType(1)
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L);

        if (commentMapper.insert(comment) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 点赞评论
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = {"songCache", "playlistCache"}, allEntries = true)
    public Result likeComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        if (comment.getLikeCount() == null) {
            comment.setLikeCount(0L);
        }
        comment.setLikeCount(comment.getLikeCount() + 1);

        if (commentMapper.updateById(comment) == 0) {
            return Result.error(MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 取消点赞评论
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = {"songCache", "playlistCache"}, allEntries = true)
    public Result cancelLikeComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        if (comment.getLikeCount() == null) {
            comment.setLikeCount(0L);
        }
        comment.setLikeCount(comment.getLikeCount() - 1);

        if (commentMapper.updateById(comment) == 0) {
            return Result.error(MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 删除评论
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = {"songCache", "playlistCache"}, allEntries = true)
    public Result deleteComment(Long commentId) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);

        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        if (!Objects.equals(comment.getUserId(), userId)) {
            return Result.error(MessageConstant.NO_PERMISSION);
        }

        if (commentMapper.deleteById(commentId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }
}
