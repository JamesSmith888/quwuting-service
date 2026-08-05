package org.quwuting.quwutingservice.venuefeedback.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.AdminReportResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一用户上报服务（原"场所信息纠错反馈"，2026-08-05 泛化）。
 * <p>
 * 职责：
 * <ul>
 *   <li>用户侧：提交上报（createFeedback）——任何登录用户均可，校验场所存在</li>
 *   <li>管理侧：平台级列表（listAdminReports，状态/类型组合筛选分页）、
 *       处理（resolveReport）/ 忽略（dismissReport）——均 requireAdmin</li>
 * </ul>
 * 状态机：PENDING → RESOLVED / DISMISSED，终态固定不可回退（重复操作幂等）。
 */
@Service
@RequiredArgsConstructor
public class VenueFeedbackService {

    private static final String VENUE_GONE_NAME = "已下架场所";

    private final VenueFeedbackRepository venueFeedbackRepository;
    private final VenueRepository venueRepository;
    private final ReportsProperties reportsProperties;

    /**
     * 提交上报（需登录）。
     * 校验场所存在（逻辑删除的场所不允许上报）后创建记录。
     * 响应携带 maintenanceHint（维护承诺，天数来自配置）——前端提交后直接展示。
     */
    @Transactional
    public VenueFeedbackResponse createFeedback(Long venueId, CreateFeedbackRequest request) {
        UserContext.requireAuth();
        // 校验场所存在（逻辑删除的场所不允许上报）
        if (!venueRepository.findByIdAndDeletedFalse(venueId).isPresent()) {
            throw new BusinessException(1001, "场所不存在");
        }
        VenueFeedback feedback = new VenueFeedback();
        feedback.setVenueId(venueId);
        feedback.setUserId(UserContext.getCurrentUserId());
        feedback.setType(request.type());
        feedback.setNote(request.note());
        feedback.setStatus(ReportStatus.PENDING);
        VenueFeedback saved = venueFeedbackRepository.save(feedback);
        return toResponse(saved, maintenanceHint());
    }

    /**
     * 平台级上报列表（管理端，需 ADMIN）。
     * 状态 / 类型均为可选筛选；分页倒序（最新在前）；场所名称批量查询消除 N+1。
     */
    @Transactional(readOnly = true)
    public Page<AdminReportResponse> listAdminReports(ReportStatus status, FeedbackType type, int page, int size) {
        UserContext.requireAdmin();
        // 空 spec = 恒真谓词（cb.conjunction），避免 Specification.where(null) 在新旧
        // API（Specification / PredicateSpecification）间的静态重载歧义
        Specification<VenueFeedback> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        Page<VenueFeedback> result = venueFeedbackRepository.findAll(
                spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        // 批量查场所名称（消除 N+1）
        List<Long> venueIds = result.getContent().stream()
                .map(VenueFeedback::getVenueId).distinct().toList();
        Map<Long, String> nameMap = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                        .collect(Collectors.toMap(Venue::getId, Venue::getName, (a, b) -> a));

        return result.map(f -> toAdminResponse(f,
                nameMap.getOrDefault(f.getVenueId(), VENUE_GONE_NAME)));
    }

    /** 标记上报为已处理（需 ADMIN，幂等：终态重复操作直接返回） */
    @Transactional
    public void resolveReport(Long id) {
        handleByAdmin(id, ReportStatus.RESOLVED);
    }

    /** 标记上报为已忽略（需 ADMIN，幂等：终态重复操作直接返回） */
    @Transactional
    public void dismissReport(Long id) {
        handleByAdmin(id, ReportStatus.DISMISSED);
    }

    /**
     * 处理动作公共实现：仅 PENDING 可流转；RESOLVED/DISMISSED 为终态，
     * 重复处理幂等返回（不抛错、不覆盖已有处理人/时间）。
     */
    private void handleByAdmin(Long id, ReportStatus target) {
        Long adminId = UserContext.requireAdmin();
        VenueFeedback feedback = venueFeedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (feedback.getStatus() != ReportStatus.PENDING) {
            return; // 终态幂等：不重复流转
        }
        feedback.setStatus(target);
        feedback.setHandledBy(adminId);
        feedback.setHandledAt(LocalDateTime.now());
        venueFeedbackRepository.save(feedback);
    }

    /** 维护承诺文案：天数来自配置 app.reports.maintenance-days（唯一事实源） */
    private String maintenanceHint() {
        return "已通知管理员，我们会在 " + reportsProperties.maintenanceDays() + " 日内维护好";
    }

    private VenueFeedbackResponse toResponse(VenueFeedback feedback, String maintenanceHint) {
        return new VenueFeedbackResponse(
                feedback.getId(),
                feedback.getVenueId(),
                feedback.getType(),
                feedback.getType().getDisplayName(),
                feedback.getNote(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                maintenanceHint,
                feedback.getCreatedAt()
        );
    }

    private AdminReportResponse toAdminResponse(VenueFeedback feedback, String venueName) {
        return new AdminReportResponse(
                feedback.getId(),
                feedback.getVenueId(),
                venueName,
                feedback.getType(),
                feedback.getType().getDisplayName(),
                feedback.getNote(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandledAt(),
                feedback.getCreatedAt()
        );
    }
}
