package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.enums.DemandRejectReason;
import org.quwuting.quwutingservice.dancer.enums.DemandStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerServiceRepository;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.dancer.support.DemandDetailTexts;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.dto.AdminDemandDetail;
import org.quwuting.quwutingservice.points.dto.AdminDemandItem;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.quwuting.quwutingservice.points.repository.PointsUnlockRepository;
import org.quwuting.quwutingservice.points.service.ContributionService.ContributionAggregate;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 邀约中转（2026-08-26，22-invite-relay-and-auto-release）。
 * <p>
 * 语义：开启 contact_relay 的舞伴，联系方式把关权交还舞伴——客人提交邀约后
 * 不立即拿微信（PENDING），管理员在本服务支撑的<b>邀约工作台</b>看到待办，
 * 一键复制转发话术到舞伴微信，舞伴回「给/不给」后管理员一键发放/拒绝；
 * 24h 无回复由 {@link #autoRelease()} 定时降级（autoRelease=true → 自动发放 /
 * false → 告知未回复）。
 * <p>
 * <b>获批 = 解锁事件</b>：approve/自动发放时写 PointsUnlock（免费，
 * transactionId=null）——客人此后 unlock 幂等直返（PointsService 幂等分支）、
 * 舞伴解锁统计（dancer-stats）计入；客人提交邀约阶段不扣积分、不写解锁记录
 * （避免「花了积分没拿到微信」的纠纷，把关权从积分交给舞伴本人）。
 * <p>
 * <b>状态变化 = 站内信通知</b>（2026-08-26）：发放/拒绝/自动降级实际流转时同事务
 * 给客人发 {@code DEMAND_STATUS} 站内信（内容 = DemandStatus.statusText 权威文案，
 * 软关联 DEMAND 深链邀约详情页）——客人「马上能收到消息」（消息红点），无需主动
 * 刷新我的邀约；微信服务通知（订阅消息）为 22 号文档 P2，需模板 ID，另行评估。
 * <p>
 * 一致性：发放/拒绝/降级全部走 {@code updateStatusIfPending}（WHERE
 * status='PENDING' 条件更新 = 天然幂等，重复操作/并发无竞态）；写解锁记录 =
 * <b>确定性原子写入</b>（{@code PointsUnlockRepository#insertIfAbsent} 原生
 * upsert，ON CONFLICT DO NOTHING 返回受影响行数——0 = 解锁记录已存在，幂等
 * 跳过；主代码零 catch 23505。2026-08-26 根因修复：旧实现 save + catch 23505
 * 使 Hibernate 把事务标记 rollback-only，catch 吞掉异常后提交仍抛
 * UnexpectedRollbackException = 发放 HTTP 500，见下 approve() 与 22 号文档）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandRelayService {

    /** 中转超时（小时，与 PointsService.RELAY_TIMEOUT_HOURS 互证 = 24） */
    private static final int RELAY_TIMEOUT_HOURS = 24;

    /** 催办阈值：等待超 12h → 工作台行高亮「建议催办」（管理员微信催一次，
     *  让 24h 自动降级成为「催过无回应」的兜底而非「平台默默放行」） */
    private static final Duration REMIND_AFTER = Duration.ofHours(12);

    /** 待处理视图全量拉取上限（2026-08-28，V58：pending 视图 = 中转 PENDING 待发放
     *  ∪ 全舞伴反馈未核实两类异构集合，DB 层无法 UNION 分页 → 各自全量拉取（上限
     *  保护防失控）+ 内存合并排序 + 手动分页。个人项目量级（<百级）无性能风险；
     *  超限即截断，配运营规模增长时再评估 DB 层 UNION 化） */
    private static final int PENDING_FETCH_LIMIT = 500;

    private final DemandRecordRepository demandRecordRepository;
    private final DancerRepository dancerRepository;
    private final DancerServiceRepository dancerServiceRepository;
    private final UserRepository userRepository;
    private final PointsUnlockRepository unlockRepository;
    /** 站内信（2026-08-26：邀约状态变化通知客人的站内通道，同事务写入） */
    private final MessageService messageService;
    /**
     * 解锁写路径缓存失效协调器（2026-08-31 根因收敛，<b>替代旧「仅详情缓存失效」
     * 手抄样板</b>）：获批/自动发放/代找替代真实写入解锁记录后经
     * {@code afterUnlockWrite} 失效舞伴域缓存矩阵（详情族级联 + <b>列表精失效</b>
     * ——HOT 排序主导信号 = 近7天联系解锁数）。历史根因：本类旧实现只失效详情
     * 族，与 {@code PointsService#invalidateDancerStatsAfterCommit}（详情 + 列表）
     * 不对称——「多写路径 × 失效矩阵」手抄漂移，收敛为协调器单入口后无法再漏
     * （矩阵唯一事实源见 {@code DancerUnlockCacheInvalidator} 类注释）。
     * 幂等跳过（记录已存在）不需失效，不调用。
     */
    private final org.quwuting.quwutingservice.dancer.service.DancerUnlockCacheInvalidator dancerUnlockCacheInvalidator;
    /** 贡献档案（2026-08-27，docs/agents/24：转发话术信任信号——客人贡献等级称号
     *  批量聚合防 N+1，与工作台列表同页面批量取用） */
    private final ContributionService contributionService;

    /** 客人贡献等级称号（转发话术信任信号拼装；NOVICE = 无信号值，null 不拼装） */
    private static String trustLevelName(ContributionAggregate agg) {
        return agg == null || agg.level().name().equals("NOVICE") ? null : agg.levelName();
    }

    /**
     * 邀约工作台待办列表（PENDING 分页倒序，新邀约在前）。
     * 委交给 {@link #listByScope(String, int, int)}（scope=pending），避免 待处理/已处理/
     * 全部 三视图重复映射逻辑——scope 是列表查询的正交维度，与状态机解耦。
     */
    public Page<AdminDemandItem> listPending(int page, int size) {
        return listByScope("pending", page, size);
    }

    /**
     * 邀约工作台列表（按 scope 过滤；scope=pending → 待处理 / processed → 终态
     * （APPROVED/REJECTED/AUTO_RELEASED/EXPIRED）/ all → 全部中转记录（不限状态））。
     * 舞伴范围 = 全部开启中转（contact_relay=true）的舞伴；行含舞伴摘要 + 客人公开资料
     * + message 原文 + 超 12h 催办标记 + status（列表行自描述，已处理视图直接渲染状态，
     * 无需再查详情）。映射逻辑三视图共用（单一事实源），仅底层查询按 scope 选择。
     * <p>
     * 2026-08-28（V58，docs/agents/25「反馈闭环 · 管理端可见性修复」）：pending 视图
     * 从「仅中转 PENDING」扩展为「中转 PENDING 待发放 ∪ <b>全舞伴反馈未核实</b>」——
     * 客人反馈只发生在非中转舞伴的已发放/存量邀约上，若仍按中转舞伴集合过滤将零可见
     * （生产实证：feedback_requested_at 非空的邀约 100% 非中转舞伴）。两类待办合并
     * 排序（反馈待办按反馈时间倒序浮顶，最新反馈最优先处理）+ 内存分页。
     */
    public Page<AdminDemandItem> listByScope(String scope, int page, int size) {
        List<Long> relayDancerIds = dancerRepository.findRelayEnabled().stream()
                .map(Dancer::getId).toList();
        PageRequest pr = PageRequest.of(page, Math.min(Math.max(size, 1), 50));
        Page<DemandRecord> records;
        if ("processed".equals(scope)) {
            records = relayDancerIds.isEmpty() ? Page.empty(pr)
                    : demandRecordRepository.findByDancerIdsAndStatuses(relayDancerIds,
                            List.of("APPROVED", "REJECTED", "AUTO_RELEASED", "EXPIRED"), pr);
        } else if ("all".equals(scope)) {
            records = relayDancerIds.isEmpty() ? Page.empty(pr)
                    : demandRecordRepository.findByDancerIds(relayDancerIds, pr);
        } else {
            // 待处理 = 中转 PENDING ∪ 全舞伴反馈未核实（2026-08-28，V58）
            records = mergePendingTodo(relayDancerIds, pr);
        }
        List<Long> dancerIds = records.getContent().stream()
                .map(DemandRecord::getDancerId).distinct().toList();
        List<Long> userIds = records.getContent().stream()
                .map(DemandRecord::getUserId).distinct().toList();
        Map<Long, Dancer> dancerMap = dancerIds.isEmpty() ? Map.of()
                : dancerRepository.findByIds(dancerIds).stream()
                        .collect(Collectors.toMap(Dancer::getId, d -> d));
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .filter(u -> !u.isDeleted())
                        .collect(Collectors.toMap(User::getId, u -> u));
        // 2026-08-27 信任信号批量聚合（docs/agents/24）：贡献等级（一次 GROUP BY
        // 覆盖整页用户）+ 履约确认数（该客人 × 该舞伴已合作 N 次）——转发话术拼装
        // 「已确认合作 N 次 · 等级称号」，舞伴一眼判断客人诚意（防口嗨信任信号）
        Map<Long, ContributionAggregate> contributionMap =
                contributionService.aggregatesFor(userIds);
        Map<String, Long> confirmedMap = userIds.isEmpty() || dancerIds.isEmpty() ? Map.of()
                : demandRecordRepository.countConfirmedGroupByUserIdsAndDancerIds(userIds, dancerIds)
                        .stream()
                        .collect(Collectors.toMap(
                                row -> row[0] + ":" + row[1],
                                row -> (Long) row[2]));
        LocalDateTime remindBefore = LocalDateTime.now().minus(REMIND_AFTER);
        return records.map(r -> {
            Dancer dancer = dancerMap.get(r.getDancerId());
            User user = userMap.get(r.getUserId());
            long cooperationCount = confirmedMap.getOrDefault(
                    r.getUserId() + ":" + r.getDancerId(), 0L);
            return new AdminDemandItem(
                    r.getId(),
                    r.getCreatedAt(),
                    r.getDancerId(),
                    dancer != null ? dancer.getNickname() : null,
                    dancer != null ? dancer.getCity() : null,
                    r.getUserId(),
                    user != null ? user.getNickname() : null,
                    user != null ? user.getAvatarUrl() : null,
                    user != null ? Math.max(0, Duration.between(user.getCreatedAt(), LocalDateTime.now()).toDays()) : 0,
                    r.getMessage(),
                    r.getCreatedAt().isBefore(remindBefore),
                    r.getStatus(),
                    r.getRejectReason(),
                    r.getRescueRequestedAt() != null,
                    cooperationCount,
                    trustLevelName(contributionMap.get(r.getUserId())),
                    // 2026-08-27（V56，docs/agents/25「反馈闭环」）：客人反馈 code
                    // （非空 = 已提交「没加上 TA？」反馈，已自动返还扣费积分——
                    // 管理端识别需人工介入的邀约）
                    r.getGuestFeedback(),
                    // 2026-08-28（V58）：反馈是否已核实（已处理/全部视图展示
                    // 「已核实」标记；待处理视图非空 = 反馈待办行）
                    r.getGuestFeedbackHandledAt() != null);
        });
    }

    /**
     * 待处理视图合并（2026-08-28，V58，docs/agents/25「反馈闭环 · 管理端可见性
     * 修复」）：中转 PENDING 待发放 ∪ 全舞伴反馈未核实。
     * <p>
     * 两类集合<b>理论不相交</b>（PENDING 未发放的邀约客人无法反馈——反馈仅对
     * 已发放/存量邀约开放），合并仍按 id 去重防御（保险丝，避免同记录双计）。
     * 排序键 = 各自"事件时间"：反馈待办按 feedbackRequestedAt（真实世界事件，
     * 最新反馈浮顶优先处理）、PENDING 按 createdAt（新邀约在前）——统一降序混排，
     * 管理员一眼看到最新待处理事项。DB 层无法对异构集合 UNION 分页，故全量拉取
     * （上限 {@link #PENDING_FETCH_LIMIT} 保护）+ 内存合并 + 手动切片。
     */
    private Page<DemandRecord> mergePendingTodo(List<Long> relayDancerIds, PageRequest pr) {
        List<DemandRecord> pendingRelay = relayDancerIds.isEmpty() ? List.of()
                : demandRecordRepository.findPendingByDancerIds(relayDancerIds,
                        PageRequest.of(0, PENDING_FETCH_LIMIT)).getContent();
        List<DemandRecord> feedbackPending = demandRecordRepository
                .findPendingFeedback(PageRequest.of(0, PENDING_FETCH_LIMIT)).getContent();
        Map<Long, DemandRecord> unique = new LinkedHashMap<>();
        for (DemandRecord r : pendingRelay) {
            unique.putIfAbsent(r.getId(), r);
        }
        for (DemandRecord r : feedbackPending) {
            unique.putIfAbsent(r.getId(), r);
        }
        List<DemandRecord> merged = new ArrayList<>(unique.values());
        merged.sort((a, b) -> {
            LocalDateTime ka = a.getFeedbackRequestedAt() != null
                    ? a.getFeedbackRequestedAt() : a.getCreatedAt();
            LocalDateTime kb = b.getFeedbackRequestedAt() != null
                    ? b.getFeedbackRequestedAt() : b.getCreatedAt();
            return kb.compareTo(ka);
        });
        int total = merged.size();
        int from = Math.min(pr.getPageNumber() * pr.getPageSize(), total);
        int to = Math.min(from + pr.getPageSize(), total);
        return new org.springframework.data.domain.PageImpl<>(
                merged.subList(from, to), pr, total);
    }

    /**
     * 邀约单详情（GET /admin/demands/{id}；仅 ADMIN，2026-08-26 用户反馈：
     * 工作台行点击 → 完整邀约单——客人公开资料 + 舞伴摘要 + 需求四要素结构化
     * 字段（服务/时间/时长/位置 = 服务端权威详情表述，与客人侧 getMyDemand
     * 同源派生，见 {@link DemandDetailTexts}）+ demandDetailText（多行文本复制
     * 即用）+ message 原文 + over12h（超 12h 催办标记）+ status（非 PENDING 时
     * 前端禁用发放/拒绝）。
     * 隐私克制同待办列表：不下发客人真实联系方式（openId 等），只有需求文本。
     * 不存在 → 1001「邀约不存在」。
     */
    public AdminDemandDetail getDetail(Long demandId) {
        DemandRecord record = demandRecordRepository.findById(demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        Dancer dancer = dancerRepository.findByIds(List.of(record.getDancerId()))
                .stream().findFirst().orElse(null);
        User user = userRepository.findById(record.getUserId())
                .filter(u -> !u.isDeleted()).orElse(null);
        String serviceLabel = DemandDetailTexts.resolveServiceLabel(record.getServiceIds(),
                id -> dancerServiceRepository.findByIdAndDeletedFalse(id).orElse(null));
        String timeLabel = DemandDetailTexts.timeDetailLabel(record.getTimeSlots());
        String durationLabel = DemandDetailTexts.durationLabel(record.getDuration());
        String locationLabel = DemandDetailTexts.locationLabel(record.getUserLocation());
        String detailText = DemandDetailTexts.detailText(serviceLabel, timeLabel, durationLabel, locationLabel);
        // 2026-08-27 履约闭环（docs/agents/23）：该客人与该舞伴的履约确认数
        // （「与 TA 已合作 N 次」）——管理员转发邀约时可参考/告知舞伴（私域信号）
        long cooperationCount = demandRecordRepository.countConfirmedByUserAndDancer(
                record.getUserId(), record.getDancerId());
        // 2026-08-27 信任信号 + 拒绝原因（docs/agents/24）：贡献等级称号（转发话术
        // 拼装，NOVICE 无信号值 = null）；拒绝原因 code + 客人已请求替代标记
        // （已处理视图展示原因标签 + 换乘站「代找替代」操作入口）
        String contributionLevelName = trustLevelName(
                contributionService.aggregatesFor(List.of(record.getUserId())).get(record.getUserId()));
        return new AdminDemandDetail(
                record.getId(),
                record.getCreatedAt(),
                record.getDancerId(),
                dancer != null ? dancer.getNickname() : null,
                dancer != null ? dancer.getCity() : null,
                dancer != null ? dancer.getAvatarUrl() : null,
                record.getUserId(),
                user != null ? user.getNickname() : null,
                user != null ? user.getAvatarUrl() : null,
                user != null ? Math.max(0, Duration.between(user.getCreatedAt(), LocalDateTime.now()).toDays()) : 0,
                record.getCreatedAt().isBefore(LocalDateTime.now().minus(REMIND_AFTER)),
                record.getMessage(),
                serviceLabel,
                timeLabel,
                durationLabel,
                locationLabel,
                detailText,
                record.getStatus(),
                cooperationCount,
                record.getRejectReason(),
                record.getRescueRequestedAt() != null,
                contributionLevelName,
                // 2026-08-27（V56，docs/agents/25「反馈闭环」）：客人反馈 code
                // （非空 = 已提交「没加上 TA？」反馈——邀约单详情展示反馈原因，
                // 管理员据此微信侧核实介入）
                record.getGuestFeedback());
    }

    /**
     * 管理端待办总数（GET /admin/demands/pending-count；仅 ADMIN，2026-08-26：
     * me 页「邀约工作台」入口红点数据源——与 GET /admin/reports/pending-count
     * 同模式：红点只提示"有待办"，计数随发放/拒绝动作自然归零，无独立已读态）。
     * <p>
     * 2026-08-28（V58，docs/agents/25「反馈闭环 · 管理端可见性修复」）：口径扩展
     * = 中转 PENDING 待发放 + <b>全舞伴反馈未核实</b>——客人反馈不再静默落库，
     * 计入 me 页红点（管理端入口即见，无需主动打开工作台逐条翻找）。
     */
    public long countPending() {
        List<Long> relayDancerIds = dancerRepository.findRelayEnabled().stream()
                .map(Dancer::getId).toList();
        long relayPending = relayDancerIds.isEmpty() ? 0
                : demandRecordRepository.countPendingByDancerIds(relayDancerIds);
        long feedbackPending = demandRecordRepository.countPendingFeedback();
        return relayPending + feedbackPending;
    }

    /**
     * 反馈已核实（2026-08-28，V58，docs/agents/25「反馈闭环 · 管理端可见性修复」；
     * POST /admin/demands/{id}/feedback-handled，仅 ADMIN）。
     * <p>
     * 语义：客人反馈 = 管理端待办（计入红点 + 待处理视图），管理员微信侧核实
     * （联系舞伴/客人确认线下情况）完成后一键归档——置位 guest_feedback_handled_at
     * （幂等，WHERE guest_feedback_handled_at IS NULL），从待办消失进入已处理/全部
     * 视图（行显示「已核实」标记），红点计数随之归零。
     * <p>
     * 校验：邀约不存在 → 1001；该邀约无客人反馈（guestFeedback 为空）→ 1001
     * （不能把普通邀约"假装核实"归档）；重复核实 = 幂等成功静默（同
     * updateFeedbackIf「已反馈过不报错」语义，非终态操作无需两段确认）。
     */
    @Transactional
    public void markFeedbackHandled(Long demandId) {
        DemandRecord record = demandRecordRepository.findById(demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        if (record.getGuestFeedback() == null) {
            throw new BusinessException(1001, "该邀约无客人反馈");
        }
        int updated = demandRecordRepository.updateFeedbackHandledIf(demandId, LocalDateTime.now());
        if (updated == 0) {
            log.info("管理员重复核实邀约 {} 客人反馈（幂等忽略）", demandId);
            return;
        }
        log.info("管理员核实邀约 {} 客人反馈（{}，客人 {}）", demandId,
                record.getGuestFeedback(), record.getUserId());
    }

    /**
     * 发放联系方式（PENDING → APPROVED）：舞伴在微信回「给」后管理员一键操作。
     * 获批即写解锁记录（客人幂等直返 + 舞伴统计）。幂等：非 PENDING 已处理 → 1001。
     */
    @Transactional
    public void approve(Long demandId) {
        DemandRecord record = findPendingOrThrow(demandId);
        // 舞伴下架（软删）后不再发放——联系方式无意义，防误发
        dancerRepository.findByIdAndDeletedFalse(record.getDancerId())
                .orElseThrow(() -> new BusinessException(1001, "该舞伴已下架，无法发放"));
        int updated = demandRecordRepository.updateStatusIfPending(demandId, DemandStatus.APPROVED.name());
        if (updated == 0) {
            throw new BusinessException(1001, "该邀约已处理");
        }
        writeUnlockIfAbsent(record);
        notifyDemandStatus(record, DemandStatus.APPROVED, null);
        log.info("管理员发放邀约 {} 联系方式（舞伴 {}，客人 {}）", demandId, record.getDancerId(), record.getUserId());
    }

    /**
     * 拒绝（PENDING → REJECTED + 拒绝原因）：舞伴在微信回「不给」后管理员一键操作。
     * <p>
     * 2026-08-27（V55，docs/agents/24「P0 拒绝原因闭环」）：拒绝时选填原因标签
     * （DemandRejectReason code，可空 = 旧客户端/未选）——客人侧知因文案
     * （guestText「TA 暂时不方便（档期冲突）」），拒绝 = 信息而非句号；
     * reason 落库走 {@code updateRejectIfPending}（WHERE status='PENDING'
     * 天然幂等），无原因的存量行为零回归（客人侧回退通用状态文案）。
     */
    @Transactional
    public void reject(Long demandId, DemandRejectReason reason) {
        DemandRecord record = findPendingOrThrow(demandId);
        String reasonCode = reason != null ? reason.name() : null;
        int updated = demandRecordRepository.updateRejectIfPending(demandId, reasonCode);
        if (updated == 0) {
            throw new BusinessException(1001, "该邀约已处理");
        }
        notifyDemandStatus(record, DemandStatus.REJECTED, reason);
        log.info("管理员拒绝邀约 {}（舞伴 {}，客人 {}，原因 {}）", demandId,
                record.getDancerId(), record.getUserId(), reasonCode);
    }

    /**
     * 代找替代舞伴（2026-08-27，V55，docs/agents/24「换乘站」；
     * POST /admin/demands/{id}/rescue，仅 ADMIN）。
     * <p>
     * 语义：被拒/超时邀约（REJECTED/EXPIRED，含未点请求的——管理员看到记录即可
     * 主动代找）→ 管理员微信人工确认替代舞伴同意 → 平台以原邀约四要素 +
     * message <b>原样代建</b>一条新邀约（status=APPROVED 直接发放替代舞伴联系
     * 方式，写解锁记录 = 客人幂等直返 + 舞伴统计）→ 站内信通知客人（直达新邀约
     * 详情）。原邀约状态不动（语义 = 原舞伴拒绝了，替代是新的邀约）。
     * <p>
     * 幂等：部分唯一索引 idx_qwt_demand_records_rescue_origin（origin_demand_id
     * 非空唯一）= 一次救援只产出一条替代邀约，重复代建 → 1001「已为该邀约找到
     * 替代舞伴」；时间窗口：替代邀约 message 原样复用（含原时间槽，客人看详情
     * 时知晓，管理员微信确认时已同步过）。<b>无用户间通信</b>：舞伴同意 = 管理员
     * 微信线下确认，平台只代发联系方式（合规同批准制）。
     * 客人侧「请求替代」置标记 = PointsService.requestDemandRescue（我的邀约域）。
     */
    @Transactional
    public Long rescue(Long demandId, Long targetDancerId) {
        DemandRecord original = demandRecordRepository.findById(demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        DemandStatus status = DemandStatus.parseOrNull(original.getStatus());
        if (status != DemandStatus.REJECTED && status != DemandStatus.EXPIRED) {
            throw new BusinessException(1001, "仅被拒绝或超时的邀约可代找替代");
        }
        Dancer target = dancerRepository.findByIdAndDeletedFalse(targetDancerId)
                .orElseThrow(() -> new BusinessException(1001, "替代舞伴不存在或已下架"));
        if (demandRecordRepository.existsByOriginDemandId(demandId)) {
            throw new BusinessException(1001, "已为该邀约找到替代舞伴");
        }
        // 以原邀约四要素 + message 原样代建替代邀约（status=APPROVED 直接发放：
        // 管理员已微信确认替代舞伴同意；originDemandId 溯源 = 「我的邀约」平台代找标记）
        DemandRecord rescued = new DemandRecord();
        rescued.setUserId(original.getUserId());
        rescued.setDancerId(targetDancerId);
        rescued.setServiceIds(original.getServiceIds());
        rescued.setTimeSlots(original.getTimeSlots());
        rescued.setDuration(original.getDuration());
        rescued.setUserLocation(original.getUserLocation());
        rescued.setMessage(original.getMessage());
        rescued.setStatus(DemandStatus.APPROVED.name());
        rescued.setOriginDemandId(demandId);
        DemandRecord saved = demandRecordRepository.save(rescued);
        // 获批 = 解锁事件（免费 transactionId=null）：客人幂等直返 + 舞伴统计；
        // insertIfAbsent 对重复（客人在替代舞伴处已解锁过）幂等跳过
        writeUnlockIfAbsent(saved);
        notifyRescued(original, target, saved.getId());
        log.info("管理员为邀约 {} 代找替代：{}（舞伴 {}，客人 {}）", demandId,
                targetDancerId, targetDancerId, original.getUserId());
        return saved.getId();
    }

    /**
     * 定时自动降级（每 5 分钟，@Scheduled 入口）：超 24h 仍 PENDING 的邀约，
     * 按舞伴 autoRelease 置 AUTO_RELEASED（自动发放，写解锁记录）/ EXPIRED
     * （告知未回复）。返回本次处理条数（日志/测试观测）。
     */
    @Transactional
    public int autoRelease() {
        List<DemandRecord> overdue = demandRecordRepository.findPendingOlderThan(
                LocalDateTime.now().minusHours(RELAY_TIMEOUT_HOURS));
        if (overdue.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (DemandRecord record : overdue) {
            Dancer dancer = dancerRepository.findByIdAndDeletedFalse(record.getDancerId()).orElse(null);
            boolean release = dancer != null && dancer.isAutoRelease();
            int updated = demandRecordRepository.updateStatusIfPending(record.getId(),
                    release ? DemandStatus.AUTO_RELEASED.name() : DemandStatus.EXPIRED.name());
            if (updated > 0) {
                if (release) {
                    writeUnlockIfAbsent(record);
                }
                notifyDemandStatus(record, release ? DemandStatus.AUTO_RELEASED : DemandStatus.EXPIRED, null);
                handled++;
                log.info("邀约 {} 超时降级：{}（舞伴 {} autoRelease={}）", record.getId(),
                        release ? "自动发放" : "告知未回复", record.getDancerId(), release);
            }
        }
        return handled;
    }

    /** 读取 PENDING 邀约（不存在/非 PENDING → 1001「该邀约已处理」） */
    private DemandRecord findPendingOrThrow(Long demandId) {
        DemandRecord record = demandRecordRepository.findById(demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        if (!DemandStatus.PENDING.name().equals(record.getStatus())) {
            throw new BusinessException(1001, "该邀约已处理");
        }
        return record;
    }

    /**
     * 获批写解锁记录（免费 transactionId=null；获批即解锁 = 客人幂等直返 +
     * 舞伴统计）。<b>确定性原子写入</b>（2026-08-26 根因修复）：旧实现
     * {@code save + catch DataIntegrityViolationException}——PointsUnlock 主键
     * IDENTITY，persist 即 INSERT，撞唯一索引（同 user×dancer 此前已获批过）抛异常
     * 后 Hibernate 已将事务标记 rollback-only，catch 吞掉异常仍提交失败 =
     * UnexpectedRollbackException → HTTP 500（生产实证，见 22 号文档「工作台发放
     * 500 根因」）。改用仓库原子 upsert（ON CONFLICT DO NOTHING 返回受影响行数）：
     * 0 = 解锁记录已存在（幂等跳过），1 = 真实写入。主代码零 catch 23505
     * （对齐项目确定性幂等范式，同 PointsTransactionRepository#upsertEarn）。
     */
    private void writeUnlockIfAbsent(DemandRecord record) {
        int rows = unlockRepository.insertIfAbsent(record.getUserId(),
                PointsGateTargetType.DANCER_CONTACT.name(), record.getDancerId(), LocalDateTime.now());
        if (rows == 0) {
            log.info("邀约 {} 解锁记录已存在（幂等跳过）：user={} dancer={}",
                    record.getId(), record.getUserId(), record.getDancerId());
            return;
        }
        log.info("邀约 {} 写解锁记录：user={} dancer={}（免费发放）",
                record.getId(), record.getUserId(), record.getDancerId());
        // 解锁改变舞伴统计与列表排序输入（unlockStats 累计人次/人数 + HOT 排序主导
        // 信号「近7天联系解锁数」）：真实写入后经事务 afterCommit 失效舞伴域缓存矩阵
        // （对齐 PointsService#invalidateDancerStatsAfterCommit 同款边界——提交后失效
        // 保证并发读者回源必读到已提交数据；幂等跳过分支无新数据，上方已 return）
        dancerUnlockCacheInvalidator.afterUnlockWrite(record.getDancerId());
    }

    /**
     * 邀约状态站内信（2026-08-26：客人「马上能收到消息」的站内通道——管理员发放/
     * 拒绝、24h 自动降级时同事务发送给客人，驱动 me 页「消息」入口 + tabBar 未读
     * 徽标，点击直达邀约详情页，无需客人主动刷新「我的邀约」）。
     * <p>
     * 内容 = {@link DemandStatus#statusText()} 服务端权威友好文案（尊重友好原则，
     * 前端零拼接）；<b>2026-08-27 拒绝知因</b>（V55，docs/agents/24）：REJECTED 时
     * 传入原因则用 {@link DemandRejectReason#guestText()}（「TA 暂时不方便（档期
     * 冲突）」——客人知因减痛），否则回退通用状态文案（存量/未选原因兼容）；
     * 幂等 = 调用方已按 {@code updateStatusIfPending} 实际流转（返回行数 &gt; 0）
     * 守卫，重复操作/并发不重发；同事务失败整体回滚保证通知不丢。
     * 软关联 DEMAND（深链邀约详情页 pages/demand-detail?id=）。
     */
    private void notifyDemandStatus(DemandRecord record, DemandStatus status, DemandRejectReason reason) {
        String title;
        String content;
        switch (status) {
            case APPROVED -> {
                title = "邀约已通过";
                content = status.statusText();
            }
            case REJECTED -> {
                title = "邀约未通过";
                content = reason != null ? reason.guestText() : status.statusText();
            }
            case AUTO_RELEASED -> {
                title = "联系方式已自动发放";
                content = status.statusText();
            }
            case EXPIRED -> {
                title = "邀约已过期";
                content = status.statusText();
            }
            default -> {
                return; // PENDING 不通知（等待态无需打扰）
            }
        }
        messageService.create(record.getUserId(), MessageType.DEMAND_STATUS,
                title, content, "DEMAND", record.getId());
    }

    /**
     * 替代邀约站内信（2026-08-27，V55，docs/agents/24「换乘站」）：管理员代建
     * 替代邀约成功后同事务通知客人——「已为您找到新的舞伴」+ 直达新邀约详情
     * （客人在「我的邀约」看到新记录 + 联系方式已发放）。内容 = 服务端权威文案
     * （含替代舞伴昵称，前端零拼接）；与 notifyDemandStatus 同通道（DEMAND_STATUS）。
     */
    private void notifyRescued(DemandRecord original, Dancer target, Long newDemandId) {
        String title = "已为您找到新的舞伴";
        String content = "「" + target.getNickname() + "」可以接您的邀约，联系方式已发放，快去看看并联系 TA 吧";
        messageService.create(original.getUserId(), MessageType.DEMAND_STATUS,
                title, content, "DEMAND", newDemandId);
    }
}
