package cn.edu.seig.jojomusic.service.impl;

import cn.edu.seig.jojomusic.mapper.CommentMapper;
import cn.edu.seig.jojomusic.model.entity.Comment;
import cn.edu.seig.jojomusic.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论倒赞（点踩）功能的回归测试。
 *
 * <p>倒赞与点赞是两条对称的计数逻辑，历史上"取消点赞"曾出现把计数减成负数的问题，
 * 因此这里逐条钉住倒赞的行为契约：
 * <ul>
 *   <li>倒赞 +1，且 <b>不影响 likeCount</b>（两者互不干扰）；</li>
 *   <li>取消倒赞 -1，且在 0 时保持幂等、<b>不写库</b>，绝不出现负数；</li>
 *   <li>计数字段为 null 时按 0 处理；</li>
 *   <li>评论不存在时返回错误，不抛异常。</li>
 * </ul>
 */
class CommentDislikeServiceTest {

    private CommentMapper commentMapper;
    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        service = new CommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
    }

    private Comment comment(Long like, Long dislike) {
        Comment c = new Comment();
        c.setCommentId(1L);
        c.setContent("test comment");
        c.setLikeCount(like);
        c.setDislikeCount(dislike);
        return c;
    }

    // ---------- 倒赞 ----------

    @Test
    void dislikeIncrementsCount() {
        when(commentMapper.selectById(1L)).thenReturn(comment(0L, 0L));
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        Result result = service.dislikeComment(1L);

        assertEquals(0, result.getCode());
        verify(commentMapper).updateById(any(Comment.class));
    }

    @Test
    void dislikeDoesNotTouchLikeCount() {
        // 倒赞只能改 dislikeCount；一旦顺手改了 likeCount，
        // 就会出现"点踩把赞数改掉"的串扰
        Comment existing = comment(5L, 0L);
        when(commentMapper.selectById(1L)).thenReturn(existing);
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        service.dislikeComment(1L);

        assertEquals(5L, existing.getLikeCount(), "倒赞不应改变点赞数");
        assertEquals(1L, existing.getDislikeCount());
    }

    @Test
    void dislikeHandlesNullCountAsZero() {
        when(commentMapper.selectById(1L)).thenReturn(comment(0L, null));
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        Comment c = comment(0L, null);
        when(commentMapper.selectById(1L)).thenReturn(c);
        service.dislikeComment(1L);

        assertEquals(1L, c.getDislikeCount(), "null 应按 0 处理后再 +1");
    }

    @Test
    void dislikeMissingCommentReturnsError() {
        when(commentMapper.selectById(99L)).thenReturn(null);

        Result result = service.dislikeComment(99L);

        assertNotNull(result);
        assertEquals(1, result.getCode(), "不存在的评论应返回错误码");
        verify(commentMapper, never()).updateById(any(Comment.class));
    }

    // ---------- 取消倒赞 ----------

    @Test
    void cancelDislikeDecrementsCount() {
        Comment c = comment(0L, 3L);
        when(commentMapper.selectById(1L)).thenReturn(c);
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        Result result = service.cancelDislikeComment(1L);

        assertEquals(0, result.getCode());
        assertEquals(2L, c.getDislikeCount());
    }

    @Test
    void cancelDislikeFloorsAtZeroAndIsIdempotent() {
        // 已经是 0 时不应写库，避免出现负数倒赞
        Comment c = comment(0L, 0L);
        when(commentMapper.selectById(1L)).thenReturn(c);

        Result result = service.cancelDislikeComment(1L);

        assertEquals(0, result.getCode(), "幂等取消应返回成功");
        assertEquals(0L, c.getDislikeCount());
        verify(commentMapper, never()).updateById(any(Comment.class));
    }

    @Test
    void cancelDislikeHandlesNullCount() {
        Comment c = comment(0L, null);
        when(commentMapper.selectById(1L)).thenReturn(c);

        Result result = service.cancelDislikeComment(1L);

        assertEquals(0, result.getCode());
        verify(commentMapper, never()).updateById(any(Comment.class));
    }

    @Test
    void cancelDislikeDoesNotTouchLikeCount() {
        Comment c = comment(7L, 2L);
        when(commentMapper.selectById(1L)).thenReturn(c);
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        service.cancelDislikeComment(1L);

        assertEquals(7L, c.getLikeCount(), "取消倒赞不应改变点赞数");
        assertEquals(1L, c.getDislikeCount());
    }

    @Test
    void cancelDislikeMissingCommentReturnsError() {
        when(commentMapper.selectById(99L)).thenReturn(null);

        Result result = service.cancelDislikeComment(99L);

        assertNotNull(result);
        assertEquals(1, result.getCode());
    }

    // ---------- 与点赞的对称性 ----------

    @Test
    void likeAndDislikeCountsAreIndependent() {
        // 同一评论先赞后踩：两个计数各自独立累加，互不覆盖
        Comment c = comment(0L, 0L);
        when(commentMapper.selectById(1L)).thenReturn(c);
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        service.likeComment(1L);
        assertEquals(1L, c.getLikeCount());
        assertEquals(0L, c.getDislikeCount(), "点赞不应改动倒赞数");

        service.dislikeComment(1L);
        assertEquals(1L, c.getLikeCount(), "倒赞不应改动点赞数");
        assertEquals(1L, c.getDislikeCount());
    }

    @Test
    void likeNullGuardDoesNotResetDislikeCount() {
        // 回归：likeComment 里为 likeCount 兜底时，
        // 曾经错误地把 dislikeCount 一并置 0，导致已有倒赞被抹掉
        Comment c = comment(null, 4L);
        when(commentMapper.selectById(1L)).thenReturn(c);
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);

        service.likeComment(1L);

        assertEquals(1L, c.getLikeCount());
        assertEquals(4L, c.getDislikeCount(), "为点赞兜底时不得重置倒赞数");
    }
}