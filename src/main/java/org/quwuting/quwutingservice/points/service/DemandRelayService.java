package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
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
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
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

    private final DemandRecordRepository demandRecordRepository;
    private final DancerRepository dancerRepository;
    private final DancerServiceRepository dancerServiceRepository;
    private final UserRepository userRepository;
    private final PointsUnlockRepository unlockRepository;
    /** 站内信（2026-08-26：邀约状态变化通知客人的站内通道，同事务写入） */
    private final MessageService messageService;
    /** 舞伴详情缓存失效入口（2026-08-26：获批真实写入解锁记录后经 afterCommit 失效
     *  舞伴统计缓存——解锁改变 dancer-stats 输入（unlockStats 累计人次/人数），对齐
     *  PointsService 解锁写路径同款边界；幂等跳过（记录已存在）不需失效） */
    private final org.quwuting.quwutingservice.dancer.service.DancerDetailCacheService dancerDetailCacheService;

    /**
     * 邀约工作台待办列表（PENDING 分页倒序，新邀约在前）。
     * 委交给 {@link #listByScope(String, int, int)}（scope=pending），避免 待处理/已处理/
     * 全部 三视图重复映射逻辑——scope 是列表查询的正交维度，与状态机解耦。
     */
    public Page<AdminDemandItem> listPending(int page, int size) {
        return listByScope("pending", page, size);
    }

    /**
     * 邀约工作台列表（按 scope 过滤；scope=pending → PENDING / processed → 终态
     * （APPROVED/REJECTED/AUTO_RELEASED/EXPIRED）/ all → 全部中转记录（不限状态））。
     * 舞伴范围 = 全部开启中转（contact_relay=true）的舞伴；行含舞伴摘要 + 客人公开资料
     * + message 原文 + 超 12h 催办标记 + status（列表行自描述，已处理视图直接渲染状态，
     * 无需再查详情）。映射逻辑三视图共用（单一事实源），仅底层查询按 scope 选择。
     */
    public Page<AdminDemandItem> listByScope(String scope, int page, int size) {
        List<Long> relayDancerIds = dancerRepository.findRelayEnabled().stream()
                .map(Dancer::getId).toList();
        PageRequest pr = PageRequest.of(page, Math.min(Math.max(size, 1), 50));
        if (relayDancerIds.isEmpty()) {
            return Page.empty(pr);
        }
        Page<DemandRecord> records;
        if ("processed".equals(scope)) {
            records = demandRecordRepository.findByDancerIdsAndStatuses(relayDancerIds,
                    List.of("APPROVED", "REJECTED", "AUTO_RELEASED", "EXPIRED"), pr);
        } else if ("all".equals(scope)) {
            records = demandRecordRepository.findByDancerIds(relayDancerIds, pr);
        } else {
            records = demandRecordRepository.findPendingByDancerIds(relayDancerIds, pr);
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
        LocalDateTime remindBefore = LocalDateTime.now().minus(REMIND_AFTER);
        return records.map(r -> {
            Dancer dancer = dancerMap.get(r.getDancerId());
            User user = userMap.get(r.getUserId());
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
                    r.getStatus());
        });
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
                cooperationCount);
    }

    /**
     * 管理端待办总数（GET /admin/demands/pending-count；仅 ADMIN，2026-08-26：
     * me 页「邀约工作台」入口红点数据源——与 GET /admin/reports/pending-count
     * 同模式：红点只提示"有待办"，计数随发放/拒绝动作自然归零，无独立已读态）。
     * 舞伴范围 = 全部开启中转（contact_relay=true）的舞伴（与 listPending 同口径）。
     */
    public long countPending() {
        List<Long> relayDancerIds = dancerRepository.findRelayEnabled().stream()
                .map(Dancer::getId).toList();
        if (relayDancerIds.isEmpty()) {
            return 0;
        }
        return demandRecordRepository.countPendingByDancerIds(relayDancerIds);
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
        notifyDemandStatus(record, DemandStatus.APPROVED);
        log.info("管理员发放邀约 {} 联系方式（舞伴 {}，客人 {}）", demandId, record.getDancerId(), record.getUserId());
    }

    /**
     * 拒绝（PENDING → REJECTED）：舞伴在微信回「不给」后管理员一键操作。
     * 客人侧文案由 DemandStatus.statusText 派生（「TA 暂时不方便接收邀约」，
     * 中性表述 + 引导看其他舞伴，尊重友好原则）。
     */
    @Transactional
    public void reject(Long demandId) {
        DemandRecord record = findPendingOrThrow(demandId);
        int updated = demandRecordRepository.updateStatusIfPending(demandId, DemandStatus.REJECTED.name());
        if (updated == 0) {
            throw new BusinessException(1001, "该邀约已处理");
        }
        notifyDemandStatus(record, DemandStatus.REJECTED);
        log.info("管理员拒绝邀约 {}（舞伴 {}，客人 {}）", demandId, record.getDancerId(), record.getUserId());
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
                notifyDemandStatus(record, release ? DemandStatus.AUTO_RELEASED : DemandStatus.EXPIRED);
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
        // 解锁改变舞伴统计输入（unlockStats 累计人次/人数）：真实写入后经事务
        // afterCommit 失效舞伴统计缓存（对齐 PointsService#unlock 同款边界兜底——
        // 提交后失效保证并发读者回源必读到已提交数据；幂等跳过分支无新数据不需失效）
        Long dancerId = record.getDancerId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dancerDetailCacheService.invalidate(dancerId);
                }
            });
        } else {
            dancerDetailCacheService.invalidate(dancerId);
        }
    }

    /**
     * 邀约状态站内信（2026-08-26：客人「马上能收到消息」的站内通道——管理员发放/
     * 拒绝、24h 自动降级时同事务发送给客人，驱动 me 页「消息」入口 + tabBar 未读
     * 徽标，点击直达邀约详情页，无需客人主动刷新「我的邀约」）。
     * <p>
     * 内容 = {@link DemandStatus#statusText()} 服务端权威友好文案（尊重友好原则，
     * 前端零拼接）；幂等 = 调用方已按 {@code updateStatusIfPending} 实际流转（返回
     * 行数 &gt; 0）守卫，重复操作/并发不重发；同事务失败整体回滚保证通知不丢。
     * 软关联 DEMAND（深链邀约详情页 pages/demand-detail?id=）。
     */
    private void notifyDemandStatus(DemandRecord record, DemandStatus status) {
        String title;
        switch (status) {
            case APPROVED -> title = "邀约已通过";
            case REJECTED -> title = "邀约未通过";
            case AUTO_RELEASED -> title = "联系方式已自动发放";
            case EXPIRED -> title = "邀约已过期";
            default -> {
                return; // PENDING 不通知（等待态无需打扰）
            }
        }
        messageService.create(record.getUserId(), MessageType.DEMAND_STATUS,
                title, status.statusText(), "DEMAND", record.getId());
    }
}
