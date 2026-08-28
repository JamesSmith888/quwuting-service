package org.quwuting.quwutingservice.appfeedback.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;
import org.quwuting.quwutingservice.appfeedback.dto.request.CreateAppFeedbackRequest;
import org.quwuting.quwutingservice.appfeedback.dto.response.AdminAppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.dto.response.AppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.dto.response.MyAppFeedbackResponse;
import org.quwuting.quwutingservice.appfeedback.entity.AppFeedback;
import org.quwuting.quwutingservice.appfeedback.repository.AppFeedbackRepository;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 平台级意见反馈服务（2026-08-28 新增，用户需求：提供一个极其便捷好用的
 * "对整个小程序提交 BUG/建议"的入口）。
 * <p>
 * 产品原则（站在"用户没有任何动力为平台做事"的角度，低门槛设计）：
 * <ul>
 *   <li><b>匿名可提交</b>——不强推登录（与 venue_feedbacks 同一匿名决策）；
 *       未登录用户直接提交，响应 trackable=false 提示"登录后可查看处理结果"。</li>
 *   <li><b>分类即结构化</b>——四类 chips（遇到的问题/功能建议/夸奖鼓励/其他）
 *       一键选择完成一半；内容必填但允许极短（1 字也算表达）。</li>
 *   <li><b>采纳激励前置告知</b>——提交响应携带 rewardAmount/rewardHint
 *       （"被采纳可得 +N 积分"），积分数量在上报时提前告知用户
 *       （2026-08-28 用户拍板），与门店纠错采纳同额同池（app.points.feedback-reward）。</li>
 *   <li><b>承诺第一时间处理</b>——提交响应携带 maintenanceHint
 *       （"已收到！我们会第一时间处理，预计 X 日内回复"，X 来自
 *       app.reports.maintenance-days 同一承诺池）；处理结果站内信回传（闭环）。</li>
 * </ul>
 * 状态机复用 {@link ReportStatus}（PENDING → ADOPTED / ADOPTED_NO_REWARD /
 * RESOLVED / DISMISSED，终态固定），管理端三动作与门店上报体验一致。
 * <p>
 * 防刷：60s 冷却（同一身份对同一分类，身份 = userId 或 IP），与 venue_feedbacks
 * 同模式（尽力而为，多 IP 分布式刷无法拦截）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppFeedbackService {

    private static final String ANONYMOUS_NAME = "匿名用户";

    /** 提交防刷冷却窗口（同一身份对同一分类，60s 内一次） */
    private static final long APP_FEEDBACK_RATE_LIMIT_SECONDS = 60;

    /** 频控缓存（key = category:identity；putIfAbsent 原子占位） */
    private final Cache<String, Boolean> appFeedbackLimiter = Caffeine.newBuilder()
            .expireAfterWrite(APP_FEEDBACK_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final AppFeedbackRepository appFeedbackRepository;
    private final UserRepository userRepository;
    private final ReportsProperties reportsProperties;
    private final PointsProperties pointsProperties;
    private final PointsService pointsService;
    private final org.quwuting.quwutingservice.message.service.MessageService messageService;

    /**
     * 提交意见反馈（匿名可提交，不强推登录）。
     * content / imageUrl 经 TextSanitizer 清洗后入库；响应携带三件承诺/激励
     * 信息（maintenanceHint = 第一时间处理承诺、rewardAmount/rewardHint =
     * 采纳积分前置告知、trackable = 是否可追踪）。
     */
    @Transactional
    public AppFeedbackResponse createFeedback(CreateAppFeedbackRequest request) {
        Long userId = UserContext.getCurrentUserId();
        if (isRateLimited(request.category(), userId)) {
            throw new BusinessException(1006, "操作过于频繁，请稍后再试");
        }
        AppFeedback feedback = new AppFeedback();
        feedback.setUserId(userId);
        feedback.setCategory(request.category());
        feedback.setContent(TextSanitizer.sanitize(request.content()));
        feedback.setImageUrl(request.imageUrl() == null || request.imageUrl().isBlank()
                ? null
                : TextSanitizer.sanitize(request.imageUrl()));
        feedback.setStatus(ReportStatus.PENDING);
        AppFeedback saved = appFeedbackRepository.save(feedback);
        return toResponse(saved);
    }

    /**
     * 提交防刷判定：同身份（userId 或 IP）对同一分类在冷却窗口内已提交过则 true。
     * putIfAbsent 原子占位——并发首写时可能都通过（平台反馈无库内去重索引，
     * 由冷却尽力而为兜底，与匿名 venue_feedback 同语义）。
     */
    private boolean isRateLimited(AppFeedbackCategory category, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + ClientIpResolver.resolve();
        String key = category + ":" + identity;
        return appFeedbackLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }

    /**
     * 我的意见反馈记录（GET /app-feedbacks/mine，需登录）。
     * 全状态展示（含终态 + 管理员处理说明 handleNote——"第一时间处理"承诺的
     * 闭环可见性）。奖励到账额仅 ADOPTED 非空（同事务发分 + 流水幂等保证已发放）。
     */
    @Transactional(readOnly = true)
    public List<MyAppFeedbackResponse> listMyFeedbacks() {
        Long userId = UserContext.requireAuth();
        return appFeedbackRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toMyResponse)
                .toList();
    }

    /**
     * 管理端意见反馈列表（需 ADMIN，分页倒序，可按状态筛选）。
     * 上报者昵称批量查询消除 N+1（匿名 = "匿名用户"）。
     */
    @Transactional(readOnly = true)
    public Page<AdminAppFeedbackResponse> listAdminFeedbacks(ReportStatus status, int page, int size) {
        UserContext.requireAdmin();
        Specification<AppFeedback> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<AppFeedback> result = appFeedbackRepository.findAll(
                spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Long> userIds = result.getContent().stream()
                .map(AppFeedback::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : userRepository.findByIdInAndDeletedFalse(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getNickname, (a, b) -> a));

        return result.map(f -> toAdminResponse(f,
                f.getUserId() == null ? ANONYMOUS_NAME
                        : nameMap.getOrDefault(f.getUserId(), ANONYMOUS_NAME)));
    }

    /** 待处理（PENDING）意见反馈数（需 ADMIN，上报管理红点聚合数据源之一） */
    @Transactional(readOnly = true)
    public long countPendingFeedbacks() {
        UserContext.requireAdmin();
        return appFeedbackRepository.countByStatus(ReportStatus.PENDING);
    }

    /** 标记为已处理（需 ADMIN，幂等），可携带处理结果说明 */
    @Transactional
    public void resolveFeedback(Long id, HandleReportRequest request) {
        handleByAdmin(id, ReportStatus.RESOLVED, request);
    }

    /** 标记为已忽略（需 ADMIN，幂等），可携带处理结果说明 */
    @Transactional
    public void dismissFeedback(Long id, HandleReportRequest request) {
        handleByAdmin(id, ReportStatus.DISMISSED, request);
    }

    /**
     * 采纳意见反馈（需 ADMIN，幂等）。
     * reward = true / null（缺省）= 采纳并奖励（终态 ADOPTED，同事务发分——
     * PointsSourceType.APP_FEEDBACK_REWARD 独立幂等键，与门店纠错互不冲突）；
     * false = 采纳不奖励（终态 ADOPTED_NO_REWARD）。匿名反馈（userId null）
     * 被采纳不发积分（无法归属）。
     */
    @Transactional
    public void adoptFeedback(Long id, HandleReportRequest request) {
        boolean reward = request == null || request.reward() == null || request.reward();
        AppFeedback feedback = handleByAdmin(id, reward ? ReportStatus.ADOPTED : ReportStatus.ADOPTED_NO_REWARD, request);
        if (feedback == null) {
            return; // 终态幂等：不重复发分、不重复发信
        }
        if (reward && feedback.getUserId() != null) {
            pointsService.rewardAppFeedback(feedback.getUserId(), feedback.getId());
        }
    }

    /**
     * 处理动作公共实现：仅 PENDING 可流转；终态重复操作幂等返回 null
     * （不覆盖已有处理人/时间/结果说明）。状态实际流转时向上报者发送
     * APP_FEEDBACK_RESULT 站内信（同事务、幂等、匿名不通知）。
     */
    private AppFeedback handleByAdmin(Long id, ReportStatus target, HandleReportRequest request) {
        Long adminId = UserContext.requireAdmin();
        AppFeedback feedback = appFeedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "反馈不存在"));
        if (feedback.getStatus() != ReportStatus.PENDING) {
            return null; // 终态幂等
        }
        feedback.setStatus(target);
        feedback.setHandledBy(adminId);
        feedback.setHandledAt(LocalDateTime.now());
        if (request != null) {
            feedback.setHandleNote(TextSanitizer.sanitize(request.note()));
        }
        appFeedbackRepository.save(feedback);
        notifyHandled(feedback, target);
        return feedback;
    }

    /**
     * 处理结果站内信（MessageType.APP_FEEDBACK_RESULT）：状态实际流转后发送，
     * 与状态流转同事务（通知不丢失）。匿名反馈无法归属，不通知。
     * 软关联 APP_FEEDBACK（relatedType=APP_FEEDBACK + relatedId），前端深链
     * 意见反馈页「我的反馈」列表。
     */
    private void notifyHandled(AppFeedback feedback, ReportStatus target) {
        if (feedback.getUserId() == null) {
            return; // 匿名反馈无法归属，不通知
        }
        String categoryLabel = feedback.getCategory().getDisplayName();
        String title;
        String content;
        switch (target) {
            case ADOPTED -> {
                title = "反馈已采纳";
                content = "你的「" + categoryLabel + "」反馈已被采纳并奖励积分";
            }
            case ADOPTED_NO_REWARD -> {
                title = "反馈已采纳";
                content = "你的「" + categoryLabel + "」反馈已被采纳（未奖励积分）";
            }
            case RESOLVED -> {
                title = "反馈已处理";
                content = "你的「" + categoryLabel + "」反馈已处理";
            }
            case DISMISSED -> {
                title = "反馈已忽略";
                content = "你的「" + categoryLabel + "」反馈已忽略";
            }
            default -> {
                return; // 非终态（PENDING）不可能走到通知分支，防御性早退
            }
        }
        if (feedback.getHandleNote() != null && !feedback.getHandleNote().isBlank()) {
            content += "，管理员说明：" + feedback.getHandleNote();
        }
        messageService.create(feedback.getUserId(), MessageType.APP_FEEDBACK_RESULT,
                title, content, "APP_FEEDBACK", feedback.getId());
    }

    /** 第一时间处理承诺文案：天数来自配置 app.reports.maintenance-days（唯一事实源） */
    private String maintenanceHint() {
        return "已收到！我们会第一时间处理，预计 " + reportsProperties.maintenanceDays() + " 日内回复";
    }

    /** 采纳激励文案（金额来自配置 app.points.feedback-reward；与门店纠错同源） */
    private String rewardHint() {
        return pointsService.rewardHintText();
    }

    private AppFeedbackResponse toResponse(AppFeedback feedback) {
        return new AppFeedbackResponse(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getCategory().getDisplayName(),
                feedback.getContent(),
                feedback.getImageUrl(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                maintenanceHint(),
                pointsProperties.feedbackReward(),
                rewardHint(),
                feedback.getUserId() != null,
                feedback.getCreatedAt()
        );
    }

    private MyAppFeedbackResponse toMyResponse(AppFeedback feedback) {
        return new MyAppFeedbackResponse(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getCategory().getDisplayName(),
                feedback.getContent(),
                feedback.getImageUrl(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandleNote(),
                feedback.getHandledAt(),
                // 仅「采纳并奖励」（ADOPTED）到账非空
                feedback.getStatus() == ReportStatus.ADOPTED ? pointsProperties.feedbackReward() : null,
                feedback.getCreatedAt()
        );
    }

    private AdminAppFeedbackResponse toAdminResponse(AppFeedback feedback, String reporterName) {
        return new AdminAppFeedbackResponse(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getCategory().getDisplayName(),
                feedback.getContent(),
                feedback.getImageUrl(),
                reporterName,
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandleNote(),
                feedback.getHandledAt(),
                feedback.getCreatedAt()
        );
    }
}
