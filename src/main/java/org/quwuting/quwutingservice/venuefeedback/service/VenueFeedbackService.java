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
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.AdminReportResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.MyFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
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
 *       处理（resolveReport）/ 采纳（adoptReport，reward 开关）/ 忽略（dismissReport）——
 *       均 requireAdmin，处理时可携带结果说明</li>
 * </ul>
 * 状态机：PENDING → ADOPTED（采纳并奖励）/ ADOPTED_NO_REWARD（采纳不奖励）/
 * RESOLVED / DISMISSED，终态固定不可回退（重复操作幂等）。
 * <p>
 * 处理结果站内信（2026-08-10 新增，根因见 AGENTS.md「统一用户上报 → 处理结果站内信」）：
 * 状态<b>实际流转</b>时向上报者（userId 非空）发送 FEEDBACK_RESULT 站内信——与状态流转
 * 同事务（通知不丢失）、幂等（终态重复操作不重复发信）；匿名上报（userId null）无法
 * 归属，不通知（与积分奖励同一匿名边界）。处理结果说明（handleNote）随站内信回传。
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
     * V2/V8 迁移的部分唯一索引在库内兜底（见 {@code db/migration/V2__feedback_pending_dedup.sql}
     * 与 {@code V8__feedback_correction_fields.sql}）。
     * <p>
     * 冷却 key 含 field 维度（2026-08-10）：结构化纠错后同类型可携带不同字段，
     * 语义单位 = (type, field)——用户报完"门票价格"紧接着报"联系电话"（两个不同
     * 字段）属正常连续纠错，不应被 60s 冷却误伤；同字段连点仍被压制。
     */
    private static final long FEEDBACK_RATE_LIMIT_SECONDS = 60;

    /** 频控缓存（key = venueId:type:field:identity；putIfAbsent 原子占位） */
    private final Cache<String, Boolean> feedbackLimiter = Caffeine.newBuilder()
            .expireAfterWrite(FEEDBACK_RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    private final VenueFeedbackRepository venueFeedbackRepository;
    private final VenueRepository venueRepository;
    private final ReportsProperties reportsProperties;
    private final PointsProperties pointsProperties;
    private final org.quwuting.quwutingservice.points.service.PointsService pointsService;
    private final org.quwuting.quwutingservice.message.service.MessageService messageService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 提交上报（匿名可提交，不强推登录）。
     * 校验场所存在（逻辑删除的场所不允许上报）后创建记录；
     * userId 取当前登录态（null = 匿名）。note / correctedValue 经 TextSanitizer
     * 清洗后入库；field 为受控枚举（Jackson 已拦截非法值），直接落库。
     * 响应携带 maintenanceHint（维护承诺，天数来自配置）与 trackable（是否可追踪）。
     * <p>
     * 结构化纠错载荷（2026-08-10）：INACCURATE 类型可携带 field（哪个字段有误）+
     * correctedValue（用户认为正确的数据）——解决旧载荷只有自由文本 note、
     * 管理端无法机器可读核对纠错建议的问题（根因见 AGENTS.md「统一用户上报 →
     * 结构化纠错载荷」）。其余类型忽略这两个字段（不落库）。
     * <p>
     * 防刷（2026-08-07 补齐，根因见 AGENTS.md「统一用户上报 → 防刷」）：
     * ① 60s 冷却：同身份对同场所同类型同字段（field 为 null 时仅类型）在窗口内
     * 重复提交抛 1006；
     * ② 库内 PENDING 部分唯一索引兜底（V2/V8 迁移）：并发/多实例竞争窗口内撞唯一
     * 键时按去重单位（纠错场景含 field）幂等返回已有待处理记录（不重复插入），
     * 与 StatusReportService 并发模式一致。
     */
    @Transactional
    public VenueFeedbackResponse createFeedback(Long venueId, CreateFeedbackRequest request) {
        // 校验场所存在（逻辑删除的场所不允许上报）
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }
        Long userId = UserContext.getCurrentUserId();
        if (isRateLimited(venueId, request.type(), request.field(), userId)) {
            throw new BusinessException(1006, "操作过于频繁，请稍后再试");
        }
        VenueFeedback feedback = new VenueFeedback();
        feedback.setVenueId(venueId);
        // 匿名决策：未登录 → userId = null（trackable=false），登录 → 落库可追踪
        feedback.setUserId(userId);
        feedback.setType(request.type());
        feedback.setNote(TextSanitizer.sanitize(request.note()));
        // 结构化纠错载荷（仅 INACCURATE 类型承载；correctedValue 清洗后落库）
        feedback.setField(request.type() == FeedbackType.INACCURATE ? request.field() : null);
        feedback.setCorrectedValue(request.correctedValue() == null || request.correctedValue().isBlank()
                ? null
                : TextSanitizer.sanitize(request.correctedValue()));
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
            log.debug("createFeedback 并发冲突，幂等返回已有 PENDING 记录: venueId={}, userId={}, type={}, field={}",
                    venueId, userId, request.type(), request.field());
            // 回查按去重单位匹配：纠错场景（field 非空）按 (type, field)，否则按 type
            // ——避免把"同场所同类型但不同字段"的纠错误当成重复上报吞掉（V8 拆分索引根因）。
            VenueFeedback existing = feedback.getField() != null
                    ? venueFeedbackRepository
                            .findByUserIdAndVenueIdAndTypeAndFieldAndStatus(
                                    userId, venueId, request.type(), feedback.getField(), ReportStatus.PENDING)
                            .orElseThrow(() -> new IllegalStateException(
                                    "PENDING 唯一索引冲突但未找到对应记录: venueId=" + venueId
                                            + ", userId=" + userId + ", type=" + request.type()
                                            + ", field=" + feedback.getField()))
                    : venueFeedbackRepository
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
     * 提交防刷判定：同身份（userId 或 IP）对同场所同类型同字段在冷却窗口内已提交过
     * 则 true。key 含 field 维度（2026-08-10：语义单位 = (type, field)，不同字段的
     * 连续纠错不误伤；null 字段（非纠错场景）退化为 (type) 与 V2 语义一致）。
     * putIfAbsent 原子占位——并发首写时可能都通过（库内 PENDING 唯一索引兜底收口）。
     */
    private boolean isRateLimited(Long venueId, FeedbackType type, FeedbackField field, Long userId) {
        String identity = userId != null
                ? "u" + userId
                : "ip:" + ClientIpResolver.resolve();
        String key = venueId + ":" + type + ":" + field + ":" + identity;
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

    /**
     * 待处理（PENDING）上报数（2026-08-10 首页 FAB「上报管理」红点数据源，需 ADMIN）。
     * 轻量 COUNT（status 谓词由 V2 部分唯一索引覆盖），与 message 模块
     * {@code unreadCount} 同模式——红点只提示"有待办"，不拉列表；数量随
     * 管理端处理动作（采纳/已处理/忽略）自然归零，无独立已读语义。
     */
    @Transactional(readOnly = true)
    public long countPendingReports() {
        UserContext.requireAdmin();
        return venueFeedbackRepository.countByStatus(ReportStatus.PENDING);
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
     * 采纳上报（2026-08-10 V2 新增，需 ADMIN）。
     * <p>
     * 采纳 = 管理员核实并采用该上报（区别于"已处理"——处理但不采纳不发奖励）。
     * <b>发分与状态流转同一事务</b>（状态变更 + 积分发放原子，失败整体回滚，
     * 杜绝"状态已采纳但积分未发"）。匿名上报（userId null）被采纳不发积分
     * （无法归属）；已发放过（流水幂等键）不重复发。
     * <p>
     * 奖励开关（2026-08-10 管理端三动作定稿）：request.reward() 为 true / null（缺省）
     * = 采纳并奖励（终态 ADOPTED，同事务发分）；false = 采纳不奖励（终态
     * ADOPTED_NO_REWARD，不发分）——管理员对"有效但贡献有限"的上报可采纳而不发分，
     * 上报者可见「已采纳·未奖励」，与 RESOLVED（核实后未采纳）语义区分。
     */
    @Transactional
    public void adoptReport(Long id, HandleReportRequest request) {
        boolean reward = request == null || request.reward() == null || request.reward();
        VenueFeedback feedback = handleByAdmin(id, reward ? ReportStatus.ADOPTED : ReportStatus.ADOPTED_NO_REWARD, request);
        if (reward && feedback != null && feedback.getUserId() != null) {
            pointsService.rewardFeedback(feedback.getUserId(), feedback.getId());
        }
    }

    /**
     * 处理动作公共实现：仅 PENDING 可流转；四个终态（ADOPTED/ADOPTED_NO_REWARD/
     * RESOLVED/DISMISSED）为终态，重复处理幂等返回（不抛错、不覆盖已有处理人/时间/结果说明）。
     * 处理结果说明（handleNote）经 TextSanitizer 清洗后落库，随「我的上报记录」回传用户。
     * <p>
     * 状态实际流转时调用 {@link #notifyHandled} 向上报者发送处理结果站内信
     * （同事务、幂等、匿名不通知——见类注释「处理结果站内信」）。
     *
     * @return 实际发生流转的反馈实体；终态重复操作返回 null（调用方据此决定是否发分/发信）
     */
    private VenueFeedback handleByAdmin(Long id, ReportStatus target, HandleReportRequest request) {
        Long adminId = UserContext.requireAdmin();
        VenueFeedback feedback = venueFeedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (feedback.getStatus() != ReportStatus.PENDING) {
            return null; // 终态幂等：不重复流转、不重复发分、不重复发信
        }
        feedback.setStatus(target);
        feedback.setHandledBy(adminId);
        feedback.setHandledAt(LocalDateTime.now());
        if (request != null) {
            feedback.setHandleNote(TextSanitizer.sanitize(request.note()));
        }
        venueFeedbackRepository.save(feedback);
        notifyHandled(feedback, target);
        return feedback;
    }

    /**
     * 处理结果站内信（2026-08-10 新增）：状态实际流转后向上报者发送 FEEDBACK_RESULT，
     * 与状态流转同事务（事务失败整体回滚，通知不丢失——同 DancerService 状态变更通知约定）。
     * <ul>
     *   <li>匿名上报（userId null）无法归属，不通知（与积分奖励同一匿名边界）；</li>
     *   <li>正文只陈述事实：场所名 + 上报类型 + 处理结论；奖励与否按终态区分
     *       （ADOPTED 已奖励积分 / ADOPTED_NO_REWARD 未奖励积分）——奖励数额不在消息内
     *       硬编码，以积分流水（唯一事实源）为准；</li>
     *   <li>处理结果说明（handleNote，可选）追加"管理员说明："回传；</li>
     *   <li>软关联 VENUE（relatedType=VENUE + venueId），前端深链场所详情页。</li>
     * </ul>
     */
    private void notifyHandled(VenueFeedback feedback, ReportStatus target) {
        if (feedback.getUserId() == null) {
            return; // 匿名上报无法归属，不通知
        }
        String venueName = venueRepository.findByIdAndDeletedFalse(feedback.getVenueId())
                .map(Venue::getName)
                .orElse(VENUE_GONE_NAME);
        String typeLabel = feedback.getType().getDisplayName();
        String title;
        String content;
        switch (target) {
            case ADOPTED -> {
                title = "上报已采纳";
                content = "「" + venueName + "」的「" + typeLabel + "」上报已被采纳并奖励积分";
            }
            case ADOPTED_NO_REWARD -> {
                title = "上报已采纳";
                content = "「" + venueName + "」的「" + typeLabel + "」上报已被采纳（未奖励积分）";
            }
            case RESOLVED -> {
                title = "上报已处理";
                content = "「" + venueName + "」的「" + typeLabel + "」上报已处理";
            }
            case DISMISSED -> {
                title = "上报已忽略";
                content = "「" + venueName + "」的「" + typeLabel + "」上报已忽略";
            }
            default -> {
                return; // 非终态（PENDING）不可能走到通知分支，防御性早退
            }
        }
        if (feedback.getHandleNote() != null && !feedback.getHandleNote().isBlank()) {
            content += "，管理员说明：" + feedback.getHandleNote();
        }
        messageService.create(feedback.getUserId(), MessageType.FEEDBACK_RESULT,
                title, content, "VENUE", feedback.getVenueId());
    }

    /** 维护承诺文案：天数来自配置 app.reports.maintenance-days（唯一事实源） */
    private String maintenanceHint() {
        return "已通知管理员，我们会在 " + reportsProperties.maintenanceDays() + " 日内维护好";
    }

    /**
     * 采纳奖励激励文案（2026-08-12 新增，上报激励三触点）。
     * 文案唯一事实源在 PointsService.rewardHintText()（金额来自配置
     * app.points.feedback-reward）——提交响应与公开接口 GET /points/reward-hint 同源，
     * 前端零硬编码零拼接。
     */
    private String rewardHint() {
        return pointsService.rewardHintText();
    }

    private VenueFeedbackResponse toResponse(VenueFeedback feedback, String maintenanceHint) {
        return new VenueFeedbackResponse(
                feedback.getId(),
                feedback.getVenueId(),
                feedback.getType(),
                feedback.getType().getDisplayName(),
                feedback.getNote(),
                feedback.getField(),
                feedback.getField() != null ? feedback.getField().getDisplayName() : null,
                feedback.getCorrectedValue(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                maintenanceHint,
                feedback.getUserId() != null,
                pointsProperties.feedbackReward(),
                rewardHint(),
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
                feedback.getField(),
                feedback.getField() != null ? feedback.getField().getDisplayName() : null,
                feedback.getCorrectedValue(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandleNote(),
                feedback.getHandledAt(),
                // 仅「采纳并奖励」（ADOPTED）到账非空：同事务发分 + 流水幂等保证已发放
                feedback.getStatus() == ReportStatus.ADOPTED ? pointsProperties.feedbackReward() : null,
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
                feedback.getField(),
                feedback.getField() != null ? feedback.getField().getDisplayName() : null,
                feedback.getCorrectedValue(),
                feedback.getStatus(),
                feedback.getStatus().getDisplayName(),
                feedback.getHandleNote(),
                feedback.getHandledAt(),
                feedback.getCreatedAt()
        );
    }
}
