package org.quwuting.quwutingservice.venuefeedback.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.db.DbConstraintViolations;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.common.web.ClientIpResolver;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.AdminReportResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.MyFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 统一用户上报服务（原"场所信息纠错反馈"，2026-08-05 泛化；2026-08-06 补全用户侧读路径）。
 * <p>
 * 职责：
 * <ul>
 *   <li>用户侧：提交上报（createFeedback）——<b>任何用户均可（含匿名）</b>，校验场所存在；
 *       我的上报记录（listMyFeedbacks，venueId 可选过滤）——个人中心跨场所 / 详情页当前门店共用</li>
 *   <li>管理侧：平台级列表（listAdminReports，状态/类型组合筛选分页）、
 *       处理（resolveReport）/ 忽略（dismissReport）——均 requireAdmin，处理时可携带结果说明</li>
 * </ul>
 * 状态机：PENDING → RESOLVED / DISMISSED，终态固定不可回退（重复操作幂等）。
 * <p>
 * 匿名决策（2026-08-06，需求根因见 AGENTS.md「统一用户上报」）：上报不强推登录——
 * 未登录用户直接提交（userId = null，trackable = false），管理员照常处理；
 * 但匿名记录无法在个人中心回看（「我的上报记录」按 userId 查询），处理结果也无法回传，
 * 前端在匿名提交时提示"登录后上报可查看处理结果"。
 * <p>
 * 文本防注入（2026-08-06）：note / handleNote 入库前统一经
 * {@link TextSanitizer} 清洗（控制字符剥离 + trim + 截断）；SQL 注入由 JPA 参数化
 * 天然免疫，XSS 由小程序 {@code <text>} 文本节点渲染天然转义——分层约定见 TextSanitizer javadoc。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueFeedbackService {

    private static final String VENUE_GONE_NAME = "已下架场所";

    /**
     * 提交防刷冷却窗口：同一身份（登录按 userId，匿名按 IP）对同一场所同一类型
     * 在窗口内只允许提交一次。压制连点/脚本刷脏数据（尽力而为，多 IP 分布式刷
     * 无法拦截——与 view/share 频控同语义）；登录用户的强去重由
     * V2 迁移的部分唯一索引 (user_id, venue_id, type) WHERE status='PENDING'
     * 在库内兜底（见 {@code db/migration/V2__feedback_pending_dedup.sql}）。
     */
    private static final long FEEDBACK_RATE_LIMIT_SECONDS = 60;

    /** 频控缓存（key = venueId:type:identity；putIfAbsent 原子占位） */
    private final Cache<String, Boolean> feedbackLimiter = Caffeine.newBuilder()
            .expireAfterWrite(FEEDBACK_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final VenueFeedbackRepository venueFeedbackRepository;
    private final VenueRepository venueRepository;
    private final ReportsProperties reportsProperties;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 提交上报（匿名可提交，不强推登录）。
     * 校验场所存在（逻辑删除的场所不允许上报）后创建记录；
     * userId 取当前登录态（null = 匿名）。note 经 TextSanitizer 清洗后入库。
     * 响应携带 maintenanceHint（维护承诺，天数来自配置）与 trackable（是否可追踪）。
     * <p>
     * 防刷（2026-08-07 补齐，根因见 AGENTS.md「统一用户上报 → 防刷」）：
     * ① 60s 冷却：同身份对同场所同类型在窗口内重复提交抛 1006；
     * ② 库内 PENDING 部分唯一索引兜底：并发/多实例竞争窗口内撞唯一键时
     * 幂等返回已有待处理记录（不重复插入），与 StatusReportService 并发模式一致。
     */
    @Transactional
    public VenueFeedbackResponse createFeedback(Long venueId, CreateFeedbackRequest request) {
        // 校验场所存在（逻辑删除的场所不允许上报）
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }
        Long userId = UserContext.getCurrentUserId();
        if (isRateLimited(venueId, request.type(), userId)) {
            throw new BusinessException(1006, "操作过于频繁，请稍后再试");
        }
        VenueFeedback feedback = new VenueFeedback();
        feedback.setVenueId(venueId);
        // 匿名决策：未登录 → userId = null（trackable=false），登录 → 落库可追踪
        feedback.setUserId(userId);
        feedback.setType(request.type());
        feedback.setNote(TextSanitizer.sanitize(request.note()));
        feedback.setStatus(ReportStatus.PENDING);
        try {
            VenueFeedback saved = venueFeedbackRepository.save(feedback);
            return toResponse(saved, maintenanceHint());
        } catch (DataIntegrityViolationException e) {
            // PENDING 部分唯一索引兜底：仅吞唯一键并发竞态（SQLState 23505），
            // 其余完整性错误必须继续抛出（见 DbConstraintViolations 约定）。
            if (!DbConstraintViolations.isUniqueViolation(e)) {
                throw e;
            }
            // 必须清除 session 中的脏实体（null id），否则后续查询的 auto-flush 会抛 AssertionFailure
            entityManager.clear();
            log.debug("createFeedback 并发冲突，幂等返回已有 PENDING 记录: venueId={}, userId={}, type={}",
                    venueId, userId, request.type());
            VenueFeedback existing = venueFeedbackRepository
                    .findByUserIdAndVenueIdAndTypeAndStatus(
                            userId, venueId, request.type(), ReportStatus.PENDING)
                    // 冲突必有对应记录；状态异常直接抛 IllegalStateException（事务回滚，fail-fast）
                    .orElseThrow(() -> new IllegalStateException(
                            "PENDING 唯一索引冲突但未找到对应记录: venueId=" + venueId
                                    + ", userId=" + userId + ", type=" + request.type()));
            return toResponse(existing, maintenanceHint());
        }
    }

    /**
     * 提交防刷判定：同身份（userId 或 IP）对同场所同类型在冷却窗口内已提交过则 true。
     * putIfAbsent 原子占位——并发首写时可能都通过（库内 PENDING 唯一索引兜底收口）。
     */
    private boolean isRateLimited(Long venueId, FeedbackType type, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + ClientIpResolver.resolve();
        String key = venueId + ":" + type + ":" + identity;
        return feedbackLimiter.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }

    /**
     * 当前用户的上报记录（「我的上报记录」统一数据源，需登录）。
     * <p>
     * venueId 为 null 时返回跨场所全部（个人中心）；非 null 时只返回当前门店
     * （详情页弹窗）——同一查询口径两处消费，避免接口爆炸。
     * 范围：全部状态（PENDING/RESOLVED/DISMISSED）均展示——异步审核流程的每一条
     * 记录都有消费价值（待处理 = 未反馈，已处理 = 展示处理结果），与 statusReport
     * 的"已撤销不展示"语义不同（上报类型不同，见 AGENTS.md 边界章节）。
     * 场所名称批量查询消除 N+1；场所已逻辑删除时回退"已下架场所"占位。
     */
    @Transactional(readOnly = true)
    public List<MyFeedbackResponse> listMyFeedbacks(Long venueId) {
        Long userId = UserContext.requireAuth();
        List<VenueFeedback> records = venueId == null
                ? venueFeedbackRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : venueFeedbackRepository.findByUserIdAndVenueIdOrderByCreatedAtDesc(userId, venueId);

        // 批量查场所名称（消除 N+1，与 listAdminReports 同模式）
        List<Long> venueIds = records.stream()
                .map(VenueFeedback::getVenueId).distinct().toList();
        Map<Long, String> nameMap = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                        .collect(Collectors.toMap(Venue::getId, Venue::getName, (a, b) -> a));

        return records.stream()
                .map(f -> toMyResponse(f, nameMap.getOrDefault(f.getVenueId(), VENUE_GONE_NAME)))
                .toList();
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

    /** 标记上报为已处理（需 ADMIN，幂等：终态重复操作直接返回），可携带处理结果说明 */
    @Transactional
    public void resolveReport(Long id, HandleReportRequest request) {
        handleByAdmin(id, ReportStatus.RESOLVED, request);
    }

    /** 标记上报为已忽略（需 ADMIN，幂等：终态重复操作直接返回），可携带处理结果说明 */
    @Transactional
    public void dismissReport(Long id, HandleReportRequest request) {
        handleByAdmin(id, ReportStatus.DISMISSED, request);
    }

    /**
     * 处理动作公共实现：仅 PENDING 可流转；RESOLVED/DISMISSED 为终态，
     * 重复处理幂等返回（不抛错、不覆盖已有处理人/时间/结果说明）。
     * 处理结果说明（handleNote）经 TextSanitizer 清洗后落库，随「我的上报记录」回传用户。
     */
    private void handleByAdmin(Long id, ReportStatus target, HandleReportRequest request) {
        Long adminId = UserContext.requireAdmin();
        VenueFeedback feedback = venueFeedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (feedback.getStatus() != ReportStatus.PENDING) {
            return; // 终态幂等：不重复流转
        }
        feedback.setStatus(target);
        feedback.setHandledBy(adminId);
        feedback.setHandledAt(LocalDateTime.now());
        if (request != null) {
            feedback.setHandleNote(TextSanitizer.sanitize(request.note()));
        }
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
                feedback.getUserId() != null,
                feedback.getCreatedAt()
        );
    }

    private MyFeedbackResponse toMyResponse(VenueFeedback feedback, String venueName) {
        return new MyFeedbackResponse(
                feedback.getId(),
                feedback.getVenueId(),
                venueName,
                feedback.getType(),
                feedback.getType().getDisplayName(),
                feedback.getNote(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandleNote(),
                feedback.getHandledAt(),
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
                feedback.getHandleNote(),
                feedback.getHandledAt(),
                feedback.getCreatedAt()
        );
    }
}
