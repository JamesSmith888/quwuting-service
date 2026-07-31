package org.quwuting.quwutingservice.venuefeedback.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 场所信息纠错反馈服务。
 * <p>
 * 用户在详情页发现场所状态可能过时时提交反馈，
 * 管理员在管理端查看未处理反馈列表并处理。
 */
@Service
@RequiredArgsConstructor
public class VenueFeedbackService {

    private final VenueFeedbackRepository venueFeedbackRepository;
    private final VenueRepository venueRepository;

    /**
     * 提交反馈（需登录）。
     * 校验场所存在后创建反馈记录，不校验场所是否被认领——任何登录用户均可反馈。
     */
    @Transactional
    public VenueFeedbackResponse createFeedback(Long venueId, CreateFeedbackRequest request) {
        UserContext.requireAuth();
        // 校验场所存在（逻辑删除的场所不允许反馈）
        if (!venueRepository.findByIdAndDeletedFalse(venueId).isPresent()) {
            throw new BusinessException(1001, "场所不存在");
        }
        VenueFeedback feedback = new VenueFeedback();
        feedback.setVenueId(venueId);
        feedback.setUserId(UserContext.getCurrentUserId());
        feedback.setType(request.type());
        feedback.setNote(request.note());
        VenueFeedback saved = venueFeedbackRepository.save(feedback);
        return toResponse(saved);
    }

    /** 查询场所未处理反馈（管理端） */
    @Transactional(readOnly = true)
    public List<VenueFeedbackResponse> listPendingFeedbacks(Long venueId) {
        return venueFeedbackRepository.findByVenueIdAndHandledFalseOrderByCreatedAtDesc(venueId)
                .stream().map(this::toResponse).toList();
    }

    private VenueFeedbackResponse toResponse(VenueFeedback feedback) {
        return new VenueFeedbackResponse(
                feedback.getId(),
                feedback.getVenueId(),
                feedback.getType(),
                feedback.getType().getDisplayName(),
                feedback.getNote(),
                feedback.isHandled(),
                feedback.getCreatedAt()
        );
    }
}
