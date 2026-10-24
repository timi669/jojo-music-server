package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.constant.JwtClaimsConstant;
import cn.edu.seig.jojomusic.constant.MessageConstant;
import cn.edu.seig.jojomusic.mapper.CommentMapper;
import cn.edu.seig.jojomusic.model.dto.CommentPlaylistDTO;
import cn.edu.seig.jojomusic.model.dto.CommentSongDTO;
import cn.edu.seig.jojomusic.model.entity.Comment;
import cn.edu.seig.jojomusic.result.Result;
import cn.edu.seig.jojomusic.service.ICommentService;
import cn.edu.seig.jojomusic.util.ThreadLocalUtil;
import cn.edu.seig.jojomusic.util.TypeConversionUtil;
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
 * @author timi669
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
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L).setDislikeCount(0L);

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
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L).setDislikeCount(0L);

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
            // 只兜底 likeCount；不要在这里顺手动 dislikeCount，
            // 否则会把该评论已有的倒赞数抹成 0
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
     * <p>原实现直接 likeCount - 1，在重复取消或初始为 0 时会把点赞数减成负数。
     * 现限制下限为 0：已为 0 时视为幂等成功，不再写库。
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
        Long likeCount = comment.getLikeCount();
        if (likeCount == null || likeCount <= 0) {
            // 已经是 0，幂等返回成功，避免出现负数点赞
            return Result.success(MessageConstant.SUCCESS);
        }
        comment.setLikeCount(likeCount - 1);

        if (commentMapper.updateById(comment) == 0) {
            return Result.error(MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 倒赞（点踩）评论。
     *
     * <p>与点赞逻辑对称：计数量 +1。计数字段为历史遗留的 {@code Long}，
     * 可能为 null，因此先兜底为 0 再加。
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = {"songCache", "playlistCache"}, allEntries = true)
    public Result dislikeComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        if (comment.getDislikeCount() == null) {
            comment.setDislikeCount(0L);
        }
        comment.setDislikeCount(comment.getDislikeCount() + 1);

        if (commentMapper.updateById(comment) == 0) {
            return Result.error(MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 取消倒赞评论。
     *
     * <p>与 cancelLikeComment 一致，下限钳制为 0：
     * 已为 0 时视为幂等成功、不写库，避免倒赞数被减成负数。
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @CacheEvict(cacheNames = {"songCache", "playlistCache"}, allEntries = true)
    public Result cancelDislikeComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        Long dislikeCount = comment.getDislikeCount();
        if (dislikeCount == null || dislikeCount <= 0) {
            // 已经是 0，幂等返回成功
            return Result.success(MessageConstant.SUCCESS);
        }
        comment.setDislikeCount(dislikeCount - 1);

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
