package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.FeedbackDTO;
import cn.edu.seig.vibemusic.model.entity.Feedback;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
public interface IFeedbackService extends IService<Feedback> {

    // 获取反馈列表
    Result<PageResult<Feedback>> getAllFeedbacks(FeedbackDTO feedbackDTO);

    // 删除反馈
    Result deleteFeedback(Long feedbackId);

    // 批量删除反馈
    Result deleteFeedbacks(List<Long> feedbackIds);

    // 添加反馈
    Result addFeedback(String content);

}
