package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.quwuting.quwutingservice.dancer.entity.DancerService;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceSubCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DemandDuration;
import org.quwuting.quwutingservice.dancer.enums.DemandGuestFeedback;
import org.quwuting.quwutingservice.dancer.enums.DemandRejectReason;
import org.quwuting.quwutingservice.dancer.enums.DemandStatus;
import org.quwuting.quwutingservice.dancer.enums.UserLocationOption;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerServiceRepository;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.dancer.support.DemandDetailTexts;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.points.dto.*;
import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.quwuting.quwutingservice.points.entity.PointsGate;
import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.entity.PointsUnlock;
import org.quwuting.quwutingservice.points.enums.GiftCatalog;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsGateRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.points.repository.PointsUnlockRepository;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 积分核心服务（资产模型：账户 + 流水 ledger）。
 * <p>
 * 领域不变量（与 Reaction 的"每日一记"彻底分离，见 AGENTS.md「积分系统」）：
 * <ul>
 *   <li><b>余额守恒</b>：balance = earnedTotal - spentTotal；流水只追加、不可变；</li>
 *   <li><b>挣取幂等</b>：(user, source_type, source_id) 部分唯一索引兜底并发，
 *       重复发放被 SQLState 23505 吞掉（与 feedback/reaction 并发模式一致）；</li>
 *   <li><b>赠送不超扣</b>：余额原子条件更新（{@code WHERE balance >= :amt}），
 *       无锁防并发；</li>
 *   <li><b>防刷闭环</b>：打卡 UNIQUE(user,date)；赠送每日/单目标上限 + 自赠检测；
 *       上报采纳由管理员人工把关（V2 决策：不设每日条数上限）。</li>
 * </ul>
 * 所有参数（奖励值/上限/排名权重）来自 {@link PointsProperties}（配置唯一事实源）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    /** 流水来源中文标签（唯一事实源在本服务，前端只渲染） */
    private static String sourceTypeDisplay(PointsSourceType type) {
        return switch (type) {
            case DAILY_CHECK_IN -> "每日打卡";
            case FEEDBACK_REWARD -> "上报被采纳";
            case STATUS_REPORT_REWARD -> "暂停报被采纳";
            case ADMIN_ADJUST -> "平台调整";
            case GIFT -> "赠送";
            case UNLOCK -> "解锁";
            case UNLOCK_REFUND -> "解锁返还";
        };
    }

    /** 合规规则文案（后端下发唯一事实源，前端直接渲染——禁前端硬编码）。
     *  2026-08-12 礼物化：积分退化为"获取礼物的代币"，赠送语义 = 购买礼物并送出
     *  （一次性表达，不可回收、不可再流转——彻底消除资产转移语义，见 AGENTS.md）。
     *  2026-08-14 积分解锁：解锁照片/联系方式等门槛内容是积分的又一消费出口，
     *  与赠送同为"单向消耗"——积分不进任何接收方账户（不可流转准货币红线）。 */
    private static final String RULES_TEXT =
            "积分为社区贡献值，可通过每日打卡、提交信息反馈（经管理员采纳）等免费获得；"
                    + "积分用于解锁照片/联系方式等社区内容、购买礼物送给门店/舞伴，表达支持。"
                    + "积分不具备任何货币属性，不可提现、不可转让、不可兑换任何实物或服务。";

    /** 需求时间窗口天数（2026-08-25 改版：今天起 7 个具体日期快捷选项） */
    private static final int DEMAND_TIME_WINDOW_DAYS = 7;

    /** 邀约中转超时（小时，2026-08-26，22 号文档）：PENDING 超时 = 24h 无回复，
     *  按舞伴 autoRelease 开关自动降级（AUTO_RELEASED 自动发放 / EXPIRED 告知）；
     *  前端 expireAt = createdAt + 本常量，倒计时展示 */
    private static final int RELAY_TIMEOUT_HOURS = 24;

    /** 需求时间「近3天内」相对槽 code 与文案（2026-08-26 起单一事实源在
     *  {@link DemandDetailTexts}——本服务与 DemandRelayService 邀约单详情共用；
     *  别名保持既有调用点可读性，取值恒一致） */
    private static final String DEMAND_TIME_WITHIN_3_DAYS = DemandDetailTexts.TIME_WITHIN_3_DAYS;
    private static final String DEMAND_TIME_WITHIN_3_DAYS_TEXT = DemandDetailTexts.TIME_WITHIN_3_DAYS_TEXT;

    /**
     * 采纳奖励整句激励文案（2026-08-12 上报激励三触点，文案唯一事实源在本服务）。
     * 金额来自配置 app.points.feedback-reward；整句后端拼接——VenueFeedbackService
     * 提交响应与公开接口 GET /points/reward-hint 同源消费，前端零硬编码零拼接。
     */
    public String rewardHintText() {
        int reward = pointsProperties.feedbackReward();
        return "上报被采纳后可获得 " + reward + " 积分，积分可兑换礼物赠送给舞厅/舞伴";
    }

    /**
     * 上报采纳奖励提示（2026-08-12 新增，公开只读：详情页入口徽标/反馈面板激励条/
     * 提交成功提示消费）。<b>匿名可调</b>——激励对匿名用户同样有意义（登录引导场景：
     * "登录后上报，被采纳可获得 N 积分"），全局配置无隐私，不强求登录。
     */
    @Transactional(readOnly = true)
    public RewardHintResponse rewardHint() {
        return new RewardHintResponse(pointsProperties.feedbackReward(), rewardHintText());
    }

    private final PointsAccountRepository accountRepository;
    private final PointsTransactionRepository transactionRepository;
    private final DailyCheckinRepository checkinRepository;
    private final PointsGateRepository gateRepository;
    private final PointsUnlockRepository unlockRepository;
    private final VenueLookupService venueLookupService;
    private final DancerRepository dancerRepository;
    private final DancerPhotoRepository dancerPhotoRepository;
    /** 舞伴服务范围（2026-08-24 联系方式需求：需求弹层选中的服务校验 + 消息拼接） */
    private final DancerServiceRepository dancerServiceRepository;
    /** 联系方式需求记录（2026-08-24 风控留痕，随解锁落库） */
    private final DemandRecordRepository demandRecordRepository;
    private final PointsProperties pointsProperties;
    private final org.quwuting.quwutingservice.venue.service.VenueHeatService venueHeatService;
    /** 舞伴详情缓存失效入口（2026-08-19：赠送礼物到 DANCER 改变收到积分/收礼聚合、
     *  设置 DANCER_CONTACT 门槛改变联系方式门槛值——经本入口级联失效内层统计缓存，
     *  单一失效入口，见 DancerDetailCacheService javadoc） */
    private final org.quwuting.quwutingservice.dancer.service.DancerDetailCacheService dancerDetailCacheService;
    /** 运营配置（2026-08-26 联系方式「每日首免」开关，热更新即时生效） */
    private final OpsConfigService opsConfigService;

    // ─── 账户（懒创建） ─────────────────────────────────────────────────────

    /** 取账户（不存在则创建一行，初始余额 0）——所有账务入口的公共前置 */
    @Transactional
    public PointsAccount getOrCreateAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseGet(() -> accountRepository.save(createAccount(userId)));
    }

    private PointsAccount createAccount(Long userId) {
        PointsAccount account = new PointsAccount();
        account.setUserId(userId);
        account.setBalance(0);
        account.setEarnedTotal(0);
        account.setSpentTotal(0);
        return account;
    }

    // ─── 打卡 ───────────────────────────────────────────────────────────────

    /**
     * 每日打卡（幂等：今日已打卡返回 checkedIn=false，不重复发分）。
     * 新增打卡路径：插 checkin（唯一约束兜底并发）→ 账户 +奖励 → 流水（唯一键幂等），
     * 同一事务原子完成；任一失败整体回滚（不会出现"打卡成功但没发分"）。
     * <p>
     * 并发（2026-08-19 根因修复）：零点集体打卡高频并发场景下「查打卡 → 插打卡」若
     * 交错执行，后发请求的 INSERT 会撞唯一索引 23505——旧实现靠 catch + clear() 吞异常，
     * 但 Hibernate flush 失败后事务可能已被标记 rollback-only，幂等返回实际变为 HTTP 500。
     * 修复：按 user 粒度 pg_advisory_xact_lock 串行化整个打卡事务（一人一天只打一次，
     * 串行正确），使 check-then-act 原子化、23505 路径变为不可达（唯一索引仍为纵深防御）。
     */
    @Transactional
    public CheckInResponse checkIn(Long userId) {
        int reward = pointsProperties.checkInReward();
        LocalDate today = LocalDate.now();
        // 锁必须在打卡幂等检查之前获取（对齐 unlock() 同一并发范式）
        checkinRepository.lockUserCheckin("checkin:" + userId);
        DailyCheckin checkin = checkinRepository.findByUserIdAndCheckinDate(userId, today)
                .orElseGet(() -> {
                    DailyCheckin c = new DailyCheckin();
                    c.setUserId(userId);
                    c.setCheckinDate(today);
                    return checkinRepository.save(c); // 串行化后 23505 不可达；唯一索引仍为纵深防御
                });
        // 幂等发分：流水唯一键 (user, DAILY_CHECK_IN, checkinId) 保证只发一次
        if (transactionRepository.findByUserIdAndSourceTypeAndSourceId(
                userId, PointsSourceType.DAILY_CHECK_IN, checkin.getId()).isPresent()) {
            return new CheckInResponse(false, 0, getOrCreateAccount(userId).getBalance());
        }
        long balance = earn(userId, reward, PointsSourceType.DAILY_CHECK_IN, checkin.getId(), null);
        return new CheckInResponse(true, reward, balance);
    }

    // ─── 概览 / 流水 ────────────────────────────────────────────────────────

    /**
     * 积分页概览（余额 + 今日挣/赠 + 打卡态 + 规则文案）。
     * <b>纯只读、无写副作用</b>：账户不存在时返回零概览（balance=0），<b>不创建账户</b>——
     * 只读事务内执行 INSERT 会被 Postgres 拒绝（2026-08-10 生产实证：
     * "cannot execute INSERT in a read-only transaction"，见当日日志）。
     * 账户的懒创建只发生在<b>写路径</b>（checkIn/gift/earn/adjust——均为可写事务）。
     */
    @Transactional(readOnly = true)
    public PointsSummaryResponse summary(Long userId) {
        PointsAccount account = accountRepository.findByUserId(userId).orElse(null);
        long balance = account != null ? account.getBalance() : 0L;
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        long todayEarned = transactionRepository.sumEarnedToday(userId, dayStart, dayEnd);
        long todayGifted = transactionRepository.sumGiftedToday(userId, dayStart, dayEnd);
        boolean checkedInToday = checkinRepository.findByUserIdAndCheckinDate(userId, LocalDate.now()).isPresent();
        return new PointsSummaryResponse(balance, todayEarned, todayGifted, checkedInToday, RULES_TEXT);
    }

    /** 用户流水分页（type=ALL/EARN/GIFT） */
    @Transactional(readOnly = true)
    public Page<PointsTransactionResponse> listTransactions(Long userId, String type, int page, int size) {
        String filter = (type == null || type.isBlank()) ? "ALL" : type.toUpperCase();
        if (!filter.equals("ALL") && !filter.equals("EARN") && !filter.equals("GIFT")) {
            throw new BusinessException(1001, "无效的流水类型");
        }
        return transactionRepository.findPageByUserAndType(userId, filter,
                        PageRequest.of(page, Math.min(Math.max(size, 1), 50)))
                .map(this::toResponse);
    }

    private PointsTransactionResponse toResponse(PointsTransaction tx) {
        GiftCatalog gift = tx.getGiftCode() != null ? GiftCatalog.fromCode(tx.getGiftCode()).orElse(null) : null;
        return new PointsTransactionResponse(
                tx.getId(),
                tx.getDelta(),
                tx.getDelta() > 0,
                tx.getSourceType().name(),
                sourceTypeDisplay(tx.getSourceType()),
                tx.getTargetType() != null ? tx.getTargetType().name() : null,
                tx.getTargetId(),
                tx.getGiftCode(),
                gift != null ? gift.displayName() : null,
                tx.getRemark(),
                tx.getBalanceAfter(),
                tx.getCreatedAt());
    }

    // ─── 赠送（消费：购买礼物并送出，2026-08-12 礼物化） ───────────────────────

    /**
     * 赠送礼物（V2 升级：载荷从积分数量改为礼物 code——积分赠送 = 资产转移语义，
     * 触碰"可流转准货币"合规红线且无情感载体；礼物 = 一次性表达，见 AGENTS.md
     * 「积分系统 · 礼物赠送」根因）。
     * 校验链：礼物 code 合法 → 目标存在可见 → 自赠检测 → 单次/每日/单目标每日
     * （按礼物价格折算积分价值）→ 原子扣减 → 写赠送流水（同事务，gift_code 记录
     * "送了什么"）。任一失败整体回滚。
     */
    @Transactional
    public GiftResponse gift(Long userId, PointsTargetType targetType, Long targetId, String giftCode) {
        GiftCatalog gift = GiftCatalog.fromCode(giftCode)
                .orElseThrow(() -> new BusinessException(1001, "礼物不存在"));
        int amount = gift.price();
        PointsProperties.GiftLimits limits = pointsProperties.gift();
        if (amount > limits.maxPerGift()) {
            throw new BusinessException(1012, "单次最多赠送 " + limits.maxPerGift() + " 积分价值的礼物");
        }
        validateTarget(targetType, targetId, userId);

        // 同一用户赠送事务串行化（2026-08-19 根因修复，同 unlock()/checkIn() 范式）：
        // 「日/单目标日上限读检查 → 原子扣减 → 写流水」若并发交错，上限检查可同时通过、
        // 实际扣减超过配置上限（读后写竞态）。锁必须在全部校验之前获取。
        transactionRepository.lockUserGift("gift:" + userId);
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        if (transactionRepository.sumGiftedToday(userId, dayStart, dayEnd) + amount > limits.maxPerDay()) {
            throw new BusinessException(1013, "今日赠送已达上限（" + limits.maxPerDay() + " 积分价值）");
        }
        if (transactionRepository.sumGiftedToTargetToday(userId, targetType, targetId, dayStart, dayEnd)
                + amount > limits.maxPerTargetDay()) {
            throw new BusinessException(1014, "该目标今日已达赠送上限（" + limits.maxPerTargetDay() + " 积分价值）");
        }

        PointsAccount account = getOrCreateAccount(userId);
        if (account.getBalance() < amount) {
            throw new BusinessException(1011, "积分余额不足");
        }
        // 原子条件扣减（防并发超扣）：affected = 0 即余额不足（双保险，上面软检查先行）
        if (accountRepository.deductBalance(userId, amount) == 0) {
            throw new BusinessException(1011, "积分余额不足");
        }
        long newBalance = account.getBalance() - amount;
        // 赠送流水：source_type=GIFT（消费动作，见 PointsSourceType），必带 target +
        // gift_code（"送了什么"），不进挣取幂等唯一键（该键 WHERE delta > 0）
        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(userId);
        tx.setDelta(-amount);
        tx.setBalanceAfter(newBalance);
        tx.setSourceType(PointsSourceType.GIFT);
        tx.setTargetType(targetType);
        tx.setTargetId(targetId);
        tx.setGiftCode(gift.name());
        PointsTransaction savedTx = transactionRepository.save(tx);
        // 热度缓存失效延后到事务提交后（与 reaction toggle 同模式，2026-08-08 根因修复）：
        // 提交前失效存在竞态窗口——另一线程读到 cache miss → 回源重算 → 读不到本事务
        // 未提交数据 → 缓存陈旧值。afterCommit 回调在提交完成后执行，回源必读到已提交数据。
        if (targetType == PointsTargetType.VENUE) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            venueHeatService.invalidate(targetId);
                        }
                    });
        } else if (targetType == PointsTargetType.DANCER) {
            // 舞伴收礼价值趋势（pointsTrend）+ 详情收礼/收到积分聚合输入：
            // 真实赠送后失效详情缓存（同事务 afterCommit，与 VENUE 分支同模式；
            // 2026-08-19 失效入口收敛到 DancerDetailCacheService，级联内层统计缓存）
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dancerDetailCacheService.invalidate(targetId);
                        }
                    });
        }
        return new GiftResponse(newBalance, savedTx.getId(), gift.name(), gift.displayName());
    }

    /** 目标存在性 + 可见性 + 自赠检测（venue.claimedBy / dancer.createdBy == 本人 → 拒绝） */
    private void validateTarget(PointsTargetType targetType, Long targetId, Long userId) {
        if (targetType == PointsTargetType.VENUE) {
            Venue venue = venueLookupService.findById(targetId); // 不存在 → 1001
            if (venue.getClaimedBy() != null && venue.getClaimedBy().equals(userId)) {
                throw new BusinessException(1015, "不能给自己管理的门店赠送礼物");
            }
            return;
        }
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(targetId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        if (dancer.getCreatedBy().equals(userId)) {
            throw new BusinessException(1015, "不能给自己的舞伴主页赠送礼物");
        }
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1001, "该舞伴资料暂不可见");
        }
    }

    // ─── 挣取（打卡/采纳/管理加分共用） ─────────────────────────────────────

    /**
     * 挣取公共实现：账户 +delta（原子）→ 写挣取流水（幂等键兜底）。
     *
     * @return 新余额
     */
    @Transactional
    public long earn(Long userId, long delta, PointsSourceType sourceType, Long sourceId, String remark) {
        PointsAccount account = getOrCreateAccount(userId);
        accountRepository.addBalance(userId, delta);
        long newBalance = account.getBalance() + delta;
        // 挣取流水幂等写入（2026-08-20 确定性化）：命中挣取唯一索引则 DO NOTHING 返回 0 行——
        // 0 = 该来源已发过（幂等，回查该来源流水的余额快照返回），1 = 真实发放。
        // 替代旧「saveAndFlush + catch 23505 + 同事务回查」：PG 语句失败后事务中止
        // （25P02），catch 内回查必然 HTTP 500（见 15-governance 错误表）。
        // sourceType 传 name()：原生 SQL 绑定 enum 默认 ORDINAL（落库序号），
        // 回查 findByUserIdAndSourceTypeAndSourceId 按 name() 匹配必然 0 条（2026-08-20 实证）
        int rows = transactionRepository.upsertEarn(userId, delta, newBalance, sourceType.name(), sourceId, remark,
                LocalDateTime.now());
        if (rows == 0) {
            // 重复发放竞态：唯一键冲突 = 已有同来源流水，幂等返回该来源的余额快照
            // （addBalance 的累加随本事务回滚，无副作用）
            return transactionRepository
                    .findByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "积分发放唯一键冲突但未找到记录: userId=" + userId
                                    + ", sourceType=" + sourceType + ", sourceId=" + sourceId))
                    .getBalanceAfter();
        }
        return newBalance;
    }

    /**
     * 上报采纳奖励（供 VenueFeedbackService 在 ADOPTED 流转事务内调用）。
     * 匿名上报（userId null）不发分；已发过（幂等键存在）不发分。
     *
     * @return 新余额；匿名或已发过返回 null 表示未发放
     */
    @Transactional
    public Long rewardFeedback(Long feedbackUserId, Long feedbackId) {
        if (feedbackUserId == null) {
            return null; // 匿名上报无法归属
        }
        if (transactionRepository.findByUserIdAndSourceTypeAndSourceId(
                feedbackUserId, PointsSourceType.FEEDBACK_REWARD, feedbackId).isPresent()) {
            return null; // 幂等：已发放
        }
        return earn(feedbackUserId, pointsProperties.feedbackReward(),
                PointsSourceType.FEEDBACK_REWARD, feedbackId, null);
    }

    /**
     * 暂停营业报告采纳奖励（供 StatusReportService 在采纳流转事务内调用，2026-08-10）。
     * 采纳 = 管理员核实暂停属实（门店状态随之标记 SUSPENDED）；匿名上报（userId null）
     * 不发分；已发过（幂等键存在）不发分——与 {@link #rewardFeedback} 同模式。
     *
     * @return 新余额；匿名或已发过返回 null 表示未发放
     */
    @Transactional
    public Long rewardStatusReport(Long reportUserId, Long reportId) {
        if (reportUserId == null) {
            return null; // 匿名上报无法归属
        }
        if (transactionRepository.findByUserIdAndSourceTypeAndSourceId(
                reportUserId, PointsSourceType.STATUS_REPORT_REWARD, reportId).isPresent()) {
            return null; // 幂等：已发放
        }
        return earn(reportUserId, pointsProperties.statusReportReward(),
                PointsSourceType.STATUS_REPORT_REWARD, reportId, null);
    }

    // ─── 管理端调整 ─────────────────────────────────────────────────────────

    /**
     * 管理端人工调整（需 ADMIN，纠正误发/惩罚刷分）。
     * 加分：balance + delta, earnedTotal + delta；扣分：balance - |delta|（条件更新
     * 防超扣，不影响 spentTotal——spentTotal 是用户赠送统计，管理扣分不计入）。
     * 调整单号 = 流水 id（ADMIN_ADJUST 的 source_id 幂等键）。
     */
    @Transactional
    public void adjust(Long adminId, Long targetUserId, Integer delta, String reason) {
        if (delta == null || delta == 0) {
            throw new BusinessException(1001, "调整量不能为 0");
        }
        PointsAccount account = getOrCreateAccount(targetUserId);
        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(targetUserId);
        if (delta > 0) {
            accountRepository.addBalance(targetUserId, delta);
            tx.setDelta(delta);
            tx.setBalanceAfter(account.getBalance() + delta);
        } else {
            long deduct = -delta;
            if (account.getBalance() < deduct) {
                throw new BusinessException(1011, "目标用户余额不足，无法扣减");
            }
            if (accountRepository.deductBalance(targetUserId, deduct) == 0) {
                throw new BusinessException(1011, "目标用户余额不足，无法扣减");
            }
            tx.setDelta(delta);
            tx.setBalanceAfter(account.getBalance() - deduct);
        }
        tx.setSourceType(PointsSourceType.ADMIN_ADJUST);
        tx.setSourceId(adminId); // source_id = 操作管理员 id（幂等键：同一管理员重复提交同值会被唯一索引拦截）
        tx.setRemark(reason);
        PointsTransaction saved = transactionRepository.save(tx);
        log.info("管理员 {} 调整用户 {} 积分 {}（调整单 {}，原因：{}）", adminId, targetUserId, delta, saved.getId(), reason);
    }

    // ─── 目标收到礼物（"收获的支持"礼物墙，2026-08-12 礼物化） ────────────────

    /**
     * 目标收到礼物聚合（code → 件数，count 降序）——供 venue/dancer 详情
     * 「收获的支持」礼物墙展示。与 receivedTotal/receivedSince（收到积分价值，
     * 热度公式输入项）同源不同维：前者记录"送了什么"（载体），后者统计价值。
     */
    @Transactional(readOnly = true)
    public List<GiftCountResponse> receivedGifts(PointsTargetType targetType, Long targetId) {
        return transactionRepository.sumGiftsReceived(targetType, targetId).stream()
                .map(row -> new GiftCountResponse((String) row[0], (Long) row[1]))
                .toList();
    }

    /**
     * 某礼物的赠送者列表（礼物墙点击弹层/详情页，2026-08-12——公开只读社交信号）。
     * 目标可见性：venue 未软删（venueLookupService.findById 兜底 1001）/
     * dancer NORMAL；与详情页礼物墙同口径，不做登录/自赠校验（查看"谁送了"非资金操作）。
     */
    @Transactional(readOnly = true)
    public List<GifterResponse> gifters(PointsTargetType targetType, Long targetId, String giftCode) {
        GiftCatalog gift = GiftCatalog.fromCode(giftCode)
                .orElseThrow(() -> new BusinessException(1001, "礼物不存在"));
        validateTargetVisible(targetType, targetId);
        return transactionRepository.findGifters(targetType, targetId, gift.name()).stream()
                .map(row -> new GifterResponse(
                        (Long) row[0],
                        row[1] == null || ((String) row[1]).isBlank() ? "舞友" : (String) row[1],
                        (String) row[2],
                        (Long) row[3],
                        (LocalDateTime) row[4]))
                .toList();
    }

    /** 目标存在性 + 可见性校验（只读场景；自赠检测仅限赠送动作，见 gift()） */
    private void validateTargetVisible(PointsTargetType targetType, Long targetId) {
        if (targetType == PointsTargetType.VENUE) {
            venueLookupService.findById(targetId); // 不存在 → 1001
            return;
        }
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(targetId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1001, "该舞伴资料暂不可见");
        }
    }

    // ─── 目标收到积分（热度公式 / 详情展示 / 趋势同源口径） ───────────────────

    /** 目标收到积分（全量 / 近30天窗口）——供热度/舞伴详情使用 */
    @Transactional(readOnly = true)
    public long receivedTotal(PointsTargetType targetType, Long targetId) {
        return transactionRepository.sumReceivedTotal(targetType, targetId);
    }

    @Transactional(readOnly = true)
    public long receivedSince(PointsTargetType targetType, Long targetId, LocalDateTime since, LocalDateTime until) {
        return transactionRepository.sumReceivedSince(targetType, targetId, since, until);
    }

    // ─── 积分解锁（2026-08-14 公共模块：门槛设置 + 解锁消费，单向燃烧） ─────────

    /**
     * 设置/更新/清除积分门槛（POST /points/gates）。
     * <ul>
     *   <li>cost &gt; 0 = 设置/更新（upsert，同目标幂等覆盖；≤ app.points.gate.max-cost）；</li>
     *   <li>cost = 0 = 清除门槛（软删行——"免费查看"）。</li>
     * </ul>
     * 权限：目标属主（舞伴本人 createdBy）或平台管理员——设置门槛 = "管理自己内容"，
     * 与 dancer 域 canManage 语义一致（普通用户不可为他人内容设门槛）。
     */
    @Transactional
    public void upsertGate(Long userId, PointsGateTargetType targetType, Long targetId,
                           int cost, UserRole currentRole) {
        if (cost < 0) {
            throw new BusinessException(1001, "门槛积分不能为负数");
        }
        if (cost > 0 && cost > pointsProperties.gate().maxCost()) {
            throw new BusinessException(1001, "门槛积分最高 " + pointsProperties.gate().maxCost());
        }
        Dancer dancer = resolveGateOwner(targetType, targetId); // 目标不存在/无门槛资格 → 1001
        if (currentRole != UserRole.ADMIN && !dancer.getCreatedBy().equals(userId)) {
            throw new BusinessException(1003, "仅舞伴本人或管理员可设置积分门槛");
        }
        PointsGate gate = gateRepository.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
        if (cost == 0) {
            if (gate != null && !gate.isDeleted()) {
                gate.setDeleted(true);
                gate.setUpdatedBy(userId);
                gateRepository.save(gate);
                invalidateDetailCacheAfterGateChange(targetType, targetId);
            }
            return; // 无门槛记录 = 本来就免费，幂等返回
        }
        if (gate == null) {
            gate = new PointsGate();
            gate.setTargetType(targetType);
            gate.setTargetId(targetId);
            gate.setCreatedBy(userId);
        }
        gate.setDeleted(false);
        gate.setCost(cost);
        gate.setUpdatedBy(userId);
        gateRepository.save(gate);
        invalidateDetailCacheAfterGateChange(targetType, targetId);
        log.info("用户 {} 设置积分门槛 {}#{} = {} 积分（舞伴 {}）", userId, targetType, targetId, cost,
                dancer.getId());
    }

    /**
     * 门槛变更后的详情缓存失效（2026-08-19）：仅 DANCER_CONTACT 门槛值在详情公共缓存内
     * （contactCost）；DANCER_PHOTO 门槛只影响相册（相册不在缓存内，每次请求实时组装），
     * 无需失效。失效经 DancerDetailCacheService 唯一入口（级联内层统计缓存）。
     */
    private void invalidateDetailCacheAfterGateChange(PointsGateTargetType targetType, Long targetId) {
        if (targetType == PointsGateTargetType.DANCER_CONTACT) {
            dancerDetailCacheService.invalidate(targetId);
        }
    }

    /**
     * 积分解锁消费（POST /points/unlock）——<b>单向燃烧</b>：扣减的积分不进任何
     * 接收方账户（若转移给舞伴即成"可流转准货币"，触碰合规红线，见 AGENTS.md
     * 「积分系统 · 积分解锁」合规决策）。舞伴获得的回报 = "有人愿为 TA 的内容
     * 花积分"的社会证明（unlocks 表支持解锁人数统计，本期不展示）。
     * <p>
     * 校验链：目标对当前用户可见 → 幂等（已解锁直接返回内容，不重复扣费）→
     * 费用计算 → 余额 → 原子扣减 → 写 UNLOCK 流水（仅真实扣费时）→ 写解锁记录。
     * <p>
     * 2026-08-24 联系方式每日首免（需求 3；2026-08-26 V49 起受运营开关
     * {@link OpsConfigService#KEY_DANCER_CONTACT_DAILY_FREE} 控制，默认 false =
     * 下线）：targetType=DANCER_CONTACT 时——
     * <ul>
     *   <li><b>无门槛舞伴恒免费</b>（gate 不存在 = admin 未设门槛，本来免费，
     *       与首免正交、不受开关影响）；</li>
     *   <li><b>有门槛舞伴每日首次获取免费</b>（开关开启时：今日已对任意有门槛
     *       舞伴解锁过 [免费或付费] 则不再免，按次消耗 contactCost；开关关闭 =
     *       一律按门槛消耗 contactCost）；</li>
     *   <li><b>已解锁过的舞伴无需重复消耗</b>（幂等分支，不重复扣费）；</li>
     *   <li>免费解锁（无门槛 / 每日首免）不写扣费流水——PointsUnlock.transaction_id
     *       为 null（V42 迁移 DROP NOT NULL，与真实扣费区分）。</li>
     * </ul>
     * 2026-08-24 联系方式需求（需求 4/5）：DANCER_CONTACT 可携带 demand
     * （服务 ≤2 + 时间 ≤2 + 时长可选）——校验服务归属后生成添加好友需求描述
     * （方案B 结构化格式）并随需求记录落库（风控留痕，见 DemandRecord）。
     * <p>
     * 并发（2026-08-19 根因修复）：按 user 粒度 pg_advisory_xact_lock 串行化整个
     * 解锁事务（一人同时解锁多目标无真实并发价值，串行正确），使 check-then-act
     * 原子化、23505 路径变为不可达（解锁记录仍保留唯一索引为纵深防御）。
     *
     * @return 解锁态 + 解锁后余额 + 解锁内容（照片原图 URL / 联系方式文本）+
     *         添加好友需求描述（DANCER_CONTACT 且携带需求时）+ 是否命中每日首免
     */
    @Transactional
    public UnlockResponse unlock(Long userId, PointsGateTargetType targetType, Long targetId,
                                 UnlockRequest.DemandSelection demand) {
        // 目标可见性 + 门槛存在性（同一处解析出内容，避免重复查询）
        UnlockTarget target = resolveUnlockTarget(targetType, targetId);
        PointsGate gate = target.gate();
        boolean contactType = targetType == PointsGateTargetType.DANCER_CONTACT;
        // 照片/视频保持原语义：必须有门槛（cost>0 且未软删）；联系方式放开门槛——
        // 无门槛舞伴 = 免费，同样走本接口（需求弹层收集需求 + 解锁记录留痕）
        if (!contactType && (gate == null || gate.isDeleted())) {
            throw new BusinessException(1001, "该内容无需积分即可查看");
        }
        // 本人/管理员归属豁免（2026-08-26 末轮：本人/管理员查看联系方式同样走邀约
        // 流程——详情页 onTapContact 一律 demand 模式，不区分视角；后端与详情组装
        // contactUnlocked 语义一致（本人/管理员恒已解锁）：直接返回内容 + 需求落库，
        // 不扣费、不写解锁记录、不消耗每日首免、不失效舞伴统计缓存）
        // 2026-08-26 邀约中转修订（用户拍板 14:30）：contactRelay 舞伴<b>全员（含
        // 本人/管理员）不豁免</b>——「邀约需舞伴批准」是舞伴设置的规则，admin/本人
        // 同样走邀约流程（管理员暂时也不给特权）；admin/本人要看联系方式走
        // dancer-edit 回显（详情接口对本人/管理员下发真实值，不依赖 unlock 豁免）。
        if (contactType && !target.contactRelay() && isOwnerOrAdmin(userId, target.ownerUserId())) {
            DemandRecorded recorded = recordDemand(userId, targetId, demand, null);
            UnlockResponse.DemandDetail demandDetail = recorded != null ? recorded.detail() : null;
            return new UnlockResponse(true, currentBalance(userId), targetType, targetId,
                    target.content(), target.contactImageUrl(),
                    demandDetail != null ? demandDetail.demandMessage() : null, false, demandDetail,
                    recorded != null ? recorded.id() : null, null, null);
        }
        // 同一用户并发解锁串行化（防「双请求同时通过幂等检查 → 双双扣费」；
        // 锁必须在幂等检查之前获取，见 repository javadoc）
        unlockRepository.lockUserUnlock("unlock:" + userId);
        // 邀约中转（2026-08-26，22 号文档）：开启 contact_relay 的舞伴——联系方式
        // 把关权交还舞伴（平台管理员微信人工转发，舞伴回「给/不给」），客人提交
        // 邀约后<b>不立即拿微信</b>，返回 PENDING 等待态。<b>全员（含本人/管理员）
        // 不豁免</b>（用户拍板 2026-08-26 14:30：管理员暂时也不给特权）。本分支在
        // 通用幂等检查之前——中转舞伴的「已发放」幂等<b>基于邀约状态而非
        // PointsUnlock</b>（开启 contact_relay 前解锁的历史记录 = PointsUnlock 存在
        // 但无获批邀约，不算获批，重走流程）：
        // ① 已获批（存在 APPROVED/AUTO_RELEASED 邀约）→ 幂等直返（重新生成本次
        //    需求描述落库，不重复扣费——同通用幂等分支行为）；
        // ② 已存在 PENDING 邀约 → 返回等待态不新建（防重复骚扰舞伴）；
        // ③ 无 → 落库 PENDING 返回等待态。
        // demand 为 null（view 直达防御路径）且未获批 → 同样返回 PENDING 语义
        // （demandId=null），绝不下发未获批的联系方式。中转模式不扣积分、不写
        // 解锁记录——获批时由 DemandRelayService 写（避免「花了积分没拿到微信」
        // 的纠纷，把关权从积分交给舞伴本人）。
        if (contactType && target.contactRelay()) {
            Optional<DemandRecord> approved = demandRecordRepository
                    .findApprovedByUserIdAndDancerId(userId, targetId);
            if (approved.isPresent()) {
                DemandRecorded recorded = recordDemand(userId, targetId, demand, null);
                UnlockResponse.DemandDetail demandDetail = recorded != null ? recorded.detail() : null;
                String demandMessage = demandDetail != null ? demandDetail.demandMessage() : null;
                return new UnlockResponse(true, currentBalance(userId), targetType, targetId,
                        target.content(), target.contactImageUrl(), demandMessage, false, demandDetail,
                        recorded != null ? recorded.id() : null, null, null);
            }
            DemandRecord pending = demandRecordRepository
                    .findPendingByUserIdAndDancerId(userId, targetId).orElse(null);
            DemandRecorded recorded = pending == null
                    ? recordDemand(userId, targetId, demand, DemandStatus.PENDING.name()) : null;
            Long demandId = pending != null ? pending.getId()
                    : (recorded != null ? recorded.id() : null);
            LocalDateTime expireAt = pending != null
                    ? pending.getCreatedAt().plusHours(RELAY_TIMEOUT_HOURS)
                    : (recorded != null && recorded.createdAt() != null
                            ? recorded.createdAt().plusHours(RELAY_TIMEOUT_HOURS) : null);
            return new UnlockResponse(false, currentBalance(userId), targetType, targetId,
                    null, null, null, false, null, demandId, DemandStatus.PENDING.name(), expireAt);
        }
        // 幂等：已解锁 → 直接返回内容（不重复扣费；串行化后此处判定确定可靠）。
        // 已解锁舞伴重新选需求（新意图/新时间）→ 重新生成需求描述并落库，不扣费。
        // 2026-08-26 邀约中转：contactRelay 舞伴已在上方中转分支处理（基于邀约
        // 状态幂等），此处仅非中转舞伴。
        if (unlockRepository.findByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId).isPresent()) {
            DemandRecorded recorded = contactType ? recordDemand(userId, targetId, demand, null) : null;
            UnlockResponse.DemandDetail demandDetail = recorded != null ? recorded.detail() : null;
            String demandMessage = demandDetail != null ? demandDetail.demandMessage() : null;
            return new UnlockResponse(true, currentBalance(userId), targetType, targetId,
                    target.content(), target.contactImageUrl(), demandMessage, false, demandDetail,
                    recorded != null ? recorded.id() : null, null, null);
        }
        // 费用计算：无门槛恒免费；有门槛联系方式每日首免（受运营开关
        // dancer.contact.daily.free 控制，V49 默认 false = 下线——暂不提供首免，
        // 一律按门槛扣费；开启后恢复：今日未对任意有门槛舞伴解锁过则首次免费）；
        // 其余按门槛扣费
        int cost = (gate != null && !gate.isDeleted()) ? gate.getCost() : 0;
        boolean freeToday = false;
        if (contactType && cost > 0
                && opsConfigService.isEnabled(OpsConfigService.KEY_DANCER_CONTACT_DAILY_FREE, false)) {
            freeToday = !hasGatedContactUnlockToday(userId);
            if (freeToday) {
                cost = 0;
            }
        }
        Long transactionId = null;
        long newBalance;
        if (cost > 0) {
            PointsAccount account = getOrCreateAccount(userId);
            if (account.getBalance() < cost) {
                throw new BusinessException(1011, "积分余额不足");
            }
            if (accountRepository.deductBalance(userId, cost) == 0) {
                throw new BusinessException(1011, "积分余额不足");
            }
            newBalance = account.getBalance() - cost;
            PointsTransaction tx = new PointsTransaction();
            tx.setUserId(userId);
            tx.setDelta(-cost);
            tx.setBalanceAfter(newBalance);
            tx.setSourceType(PointsSourceType.UNLOCK);
            // 解锁流水不挂 target_type/target_id：PointsTargetType 是"赠送/收到积分"
            // 聚合维度（VENUE/DANCER），解锁目标（照片/联系方式）不属于该维度，硬挂
            // 会造成语义混杂；解锁行为的权威记录 = qwt_points_unlocks（含
            // transaction_id 关联本流水），此处用 remark 冗余目标便于人工审计。
            tx.setRemark(targetType.name() + ":" + targetId);
            transactionId = transactionRepository.save(tx).getId();
        } else {
            newBalance = currentBalance(userId);
        }
        PointsUnlock unlock = new PointsUnlock();
        unlock.setUserId(userId);
        unlock.setTargetType(targetType);
        unlock.setTargetId(targetId);
        unlock.setTransactionId(transactionId); // 免费解锁为 null（V42 DROP NOT NULL）
        unlockRepository.save(unlock); // 串行化后 23505 不可达；唯一索引仍为纵深防御
        log.info("用户 {} 解锁 {}#{}，消耗 {} 积分（流水 {}）", userId, targetType, targetId, cost, transactionId);
        // 解锁改变舞伴统计输入（unlockStats 累计人次/人数）：真实写入后经事务
        // afterCommit 失效舞伴统计缓存（对齐 DancerViewService 同款边界兜底——
        // 提交后失效保证并发读者回源必读到已提交数据；幂等分支无新数据不需失效）
        invalidateDancerStatsAfterCommit(targetType, targetId);
        UnlockResponse.DemandDetail demandDetail = contactType ? recordDemandDetail(userId, targetId, demand, null) : null;
        String demandMessage = demandDetail != null ? demandDetail.demandMessage() : null;
        return new UnlockResponse(true, newBalance, targetType, targetId,
                target.content(), target.contactImageUrl(), demandMessage, freeToday, demandDetail,
                null, null, null);
    }

    /**
     * 我的邀约（2026-08-26，个人中心「我的邀约」列表数据源）。
     * <p>
     * 语义：需求记录 = 用户自己的行为记录（我向谁提了什么需求），按 userId 过滤天然
     * 隔离（只返回本人记录，前端零权限判定）；分页倒序（新记录在前，idx_qwt_demand_records_user
     * 索引）。
     * <p>
     * 舞伴摘要：批量 IN 查询一次取整页舞伴（规避 N+1，dancerRepository.findByIds 已
     * 过滤 deleted）；舞伴软删 → 摘要 null（前端回退「舞伴已下架」占位禁跳）；dancerVisible
     * = 未软删且 status=NORMAL（普通用户可跳详情的唯一口径，PENDING/HIDDEN/REJECTED 仅
     * 本人/管理员可见——边缘情况：本人/管理员自己的非 NORMAL 舞伴记录同样显示不可跳，
     * 可接受）。
     */
    public Page<DemandRecordResponse> listMyDemands(Long userId, int page, int size) {
        Page<DemandRecord> records = demandRecordRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(page, Math.min(Math.max(size, 1), 50)));
        List<Long> dancerIds = records.getContent().stream()
                .map(DemandRecord::getDancerId).distinct().toList();
        Map<Long, Dancer> dancerMap = dancerIds.isEmpty() ? Map.of()
                : dancerRepository.findByIds(dancerIds).stream()
                        .collect(Collectors.toMap(Dancer::getId, d -> d));
        return records.map(r -> {
            Dancer dancer = dancerMap.get(r.getDancerId());
            return new DemandRecordResponse(
                    r.getId(),
                    r.getDancerId(),
                    dancer != null ? dancer.getNickname() : null,
                    dancer != null ? dancer.getAvatarUrl() : null,
                    dancer != null ? dancer.getCity() : null,
                    dancer != null && dancer.getStatus() == DancerStatus.NORMAL,
                    r.getMessage(),
                    r.getStatus(),
                    r.getCreatedAt(),
                    r.getOriginDemandId(),
                    r.getRejectReason());
        });
    }

    /**
     * 我的单条邀约详情（2026-08-26，邀约详情页数据源——点击邀约进<b>详情</b>
     * 而非舞伴主页，见 20 号文档「我的邀约」）。
     * <p>
     * 归属校验：findByUserIdAndId 双重条件（userId + id）——越权/不存在 → 1001「邀约
     * 不存在」（邀约是用户级资源，前端零权限判定）。
     * <p>
     * 需求四要素从落库枚举/id 串反推（recordDemand 上下文的镜像）：
     * <ul>
     *   <li><b>服务</b>：历史记录<b>未存 subCategory</b>（落库仅 serviceIds），无法还原
     *       「按时段 · KTV」子选项——用服务<b>当前权威 label</b> 兜底（与详情页服务卡
     *       同源，buildServiceLabel）；服务已软删/下架 → null（前端省略该行）；</li>
     *   <li><b>时间</b>：WITHIN_3_DAYS 或具体日期 → 详情表述（补「可协商」）；</li>
     *   <li><b>时长/位置</b>：枚举 code → display/detailText 详情表述；历史数据枚举异常
     *       → 防御性 null（不打断详情页，parse 的 1001 语义只属于实时提交路径）。</li>
     * </ul>
     * 舞伴摘要与 dancerVisible 口径同 listMyDemands（软删 null / 未软删且 NORMAL 可跳）。
     */
    public DemandDetailResponse getMyDemand(Long userId, Long demandId) {
        DemandRecord record = demandRecordRepository.findByUserIdAndId(userId, demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        Dancer dancer = dancerRepository.findByIds(List.of(record.getDancerId()))
                .stream().findFirst().orElse(null);
        // 服务/时间/时长/位置详情表述（2026-08-26 抽公共方法 DemandDetailTexts——
        // 与管理员邀约单详情同源派生；serviceIds 落库恒恰好 1 项，逐段防御历史脏数据）
        String serviceLabel = DemandDetailTexts.resolveServiceLabel(record.getServiceIds(),
                id -> dancerServiceRepository.findByIdAndDeletedFalse(id).orElse(null));
        String timeDetailLabel = DemandDetailTexts.timeDetailLabel(record.getTimeSlots());
        String durationLabel = DemandDetailTexts.durationLabel(record.getDuration());
        String locationLabel = DemandDetailTexts.locationLabel(record.getUserLocation());
        // 多行详细文本（服务/时间/时长/位置，缺失行省略；与前端表格同源同序，复制即用）
        String detailText = DemandDetailTexts.detailText(serviceLabel, timeDetailLabel, durationLabel, locationLabel);
        // 2026-08-26 邀约中转（22 号文档）：状态 + 客人友好状态文案（服务端权威，
        // 前端零拼接）+ PENDING 时 expireAt（24h 降级截止）；联系方式字段仅本人 +
        // 已获批时下发（released()），其余状态恒 null——防联系方式随未获批状态泄漏。
        // 2026-08-27（V56，docs/agents/25「邀约生命周期」根因修复）：存量 NULL
        // 语义等价已发放（22 号文档明确），<b>同样下发联系方式</b>——此前
        // released = status != null && status.released() 导致非中转（绝大多数）
        // 舞伴的邀约详情页永远看不到联系方式 =「邀约单消失」的直接原因之一。
        DemandStatus status = DemandStatus.parseOrNull(record.getStatus());
        boolean released = status == null || status.released();
        // 联系方式仅获批发放时展示；dancer 软删（下架）→ 摘要已 null，不展示
        String contactText = released && dancer != null ? dancer.getContact() : null;
        String contactImageUrl = released && dancer != null ? dancer.getContactImageUrl() : null;
        // 2026-08-27 履约闭环（docs/agents/23）：fulfilledAt（本次履约确认时间，
        // null = 未确认）+ cooperationCount（该客人与该舞伴的履约确认数，含本次）
        long cooperationCount = demandRecordRepository.countConfirmedByUserAndDancer(
                userId, record.getDancerId());
        // 2026-08-27 拒绝原因 + 替代邀约（docs/agents/24）：REJECTED 且管理员已填
        // 原因时下发 rejectReason + 权威知因文案（guestText——前端 display =
        // rejectReasonText || statusText，零拼接）；rescueRequested = 客人已请求
        // 平台代找替代（终态卡按钮变已请求态）；originDemandId = 本邀约是平台代找
        // 的替代邀约（前端展示「平台代找」标识）
        DemandRejectReason rejectReason = DemandRejectReason.parseOrNull(record.getRejectReason());
        return new DemandDetailResponse(
                record.getId(),
                record.getDancerId(),
                dancer != null ? dancer.getNickname() : null,
                dancer != null ? dancer.getAvatarUrl() : null,
                dancer != null ? dancer.getCity() : null,
                dancer != null && dancer.getStatus() == DancerStatus.NORMAL,
                record.getMessage(),
                serviceLabel,
                timeDetailLabel,
                durationLabel,
                locationLabel,
                detailText.toString(),
                status != null ? status.name() : null,
                status != null ? status.statusText() : null,
                status == DemandStatus.PENDING
                        ? record.getCreatedAt().plusHours(RELAY_TIMEOUT_HOURS) : null,
                contactText,
                contactImageUrl,
                record.getCreatedAt(),
                record.getFulfilledAt(),
                cooperationCount,
                rejectReason != null ? rejectReason.name() : null,
                rejectReason != null ? rejectReason.guestText() : null,
                record.getRescueRequestedAt() != null,
                record.getOriginDemandId(),
                // 2026-08-27（V56，docs/agents/25「分享闭环自动化 + 反馈闭环」）：
                // shareOpenedAt（非空 = 舞伴已查看，客人侧「TA 已查看你的邀约」）+
                // guestFeedback/feedbackRequestedAt（非空 = 已提交「没加上 TA？」
                // 反馈，前端渲染已反馈态隐藏入口）
                record.getShareOpenedAt(),
                record.getGuestFeedback(),
                record.getFeedbackRequestedAt());
    }

    /**
     * 客人请求平台代找替代（2026-08-27，V55，docs/agents/24「换乘站」；
     * POST /points/demands/{id}/rescue-request，需登录 + 本人；我的邀约域）。
     * <p>
     * 语义：被拒/超时终态（REJECTED/EXPIRED）页客人点「让平台帮您找类似的」——
     * 置 {@code rescue_requested_at}（WHERE 双条件 = 只置一次幂等，重复请求成功
     * 不报错）→ 管理端工作台识别「客人想要续」高亮优先处理，管理员微信人工确认
     * 替代舞伴同意后代建替代邀约（DemandRelayService.rescue）。其他状态 → 1001
     * （PENDING 等待中 / APPROVED 已拿到微信，无需替代）。
     */
    public void requestDemandRescue(Long userId, Long demandId) {
        DemandRecord record = demandRecordRepository.findByUserIdAndId(userId, demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        DemandStatus status = DemandStatus.parseOrNull(record.getStatus());
        if (status == DemandStatus.REJECTED || status == DemandStatus.EXPIRED) {
            demandRecordRepository.updateRescueRequestedIf(demandId, LocalDateTime.now());
            log.info("客人 {} 请求为被拒邀约 {} 代找替代", userId, demandId);
            return;
        }
        if (status == DemandStatus.PENDING) {
            throw new BusinessException(1001, "TA 还在回复中，请稍候");
        }
        throw new BusinessException(1001, "当前状态无需寻找替代");
    }

    /**
     * 客人反馈「没加上 TA？」（2026-08-27，V56，docs/agents/25「反馈闭环」；
     * POST /points/demands/{id}/feedback，需登录 + 本人）。
     * <p>
     * 根因（25 号文档）：非中转舞伴（contact_relay=false，绝大多数）平台不感知
     * 线下结果——客人拿到微信后没加上/被拒/未回复，平台零感知、客人无出口。
     * 本方法 = 一键反馈通道（用户无动力做多余操作：提交即返还该邀约解锁时的
     * 原扣费积分，拿回自己花的分，无净收益可刷）。
     * <ul>
     *   <li>状态校验：仅已获批发放（存量 NULL / APPROVED / AUTO_RELEASED）且
     *       未履约的邀约可反馈（PENDING 等待中 / REJECTED·EXPIRED 已有知因 +
     *       换乘站 / 已履约成功 → 1001）；</li>
     *   <li>幂等：{@code updateFeedbackIf}（WHERE feedback_requested_at IS NULL）
     *       只置一次——已反馈过返回 submitted=false，不重复返还；</li>
     *   <li>返还：反查该邀约解锁记录（PointsUnlock.transactionId）的原扣费金额，
     *       {@link #earn} 一笔正分（UNLOCK_REFUND，source_id = 邀约 id——挣取
     *       幂等键兜底并发）；免费解锁（无扣费流水）无返还；</li>
     *   <li>管理端可见：AdminDemandItem/Detail 下发 guestFeedback 标记，工作台
     *       识别需人工介入的邀约。</li>
     * </ul>
     */
    @Transactional
    public FeedbackResponse requestDemandFeedback(Long userId, Long demandId,
                                                  DemandGuestFeedback reason) {
        DemandRecord record = demandRecordRepository.findByUserIdAndId(userId, demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        DemandStatus status = DemandStatus.parseOrNull(record.getStatus());
        if (status != null && !status.released()) {
            // PENDING（等待回复中）/ REJECTED·EXPIRED（已有知因文案 + 换乘站出口）
            throw new BusinessException(1001, "当前状态无需反馈");
        }
        if (record.getFulfilledAt() != null) {
            throw new BusinessException(1001, "本次邀约已完成，无需反馈");
        }
        String code = reason != null ? reason.name() : DemandGuestFeedback.OTHER.name();
        int updated = demandRecordRepository.updateFeedbackIf(demandId, code, LocalDateTime.now());
        if (updated == 0) {
            // 已反馈过（幂等成功）：不重复返还
            return new FeedbackResponse(false, false, 0);
        }
        log.info("客人 {} 反馈邀约 {}：{}", userId, demandId, code);
        long refundPoints = refundUnlockForDemand(userId, record);
        return new FeedbackResponse(true, refundPoints > 0, refundPoints);
    }

    /**
     * 返还该邀约解锁时的原扣费积分（2026-08-27，V56，docs/agents/25「反馈闭环」）。
     * 反查解锁记录（DANCER_CONTACT × dancerId）→ transactionId 非空 = 真实扣费
     * → 反查流水 delta 绝对值 = 返还金额 → {@link #earn} 正分（UNLOCK_REFUND，
     * source_id = 邀约 id，挣取唯一键幂等——重复反馈/并发只返还一次）。
     * 免费解锁（transactionId=null）/ 流水缺失或非扣费（防御历史脏数据）→ 0 不返还。
     */
    private long refundUnlockForDemand(Long userId, DemandRecord record) {
        PointsUnlock unlock = unlockRepository.findByUserIdAndTargetTypeAndTargetId(
                        userId, PointsGateTargetType.DANCER_CONTACT, record.getDancerId())
                .orElse(null);
        if (unlock == null || unlock.getTransactionId() == null) {
            return 0L;
        }
        PointsTransaction tx = transactionRepository.findById(unlock.getTransactionId()).orElse(null);
        if (tx == null || tx.getDelta() >= 0) {
            return 0L;
        }
        long amount = Math.abs(tx.getDelta());
        earn(userId, amount, PointsSourceType.UNLOCK_REFUND, record.getId(),
                "邀约 " + record.getId() + " 解锁返还");
        return amount;
    }

    /**
     * 我的进行中邀约摘要（2026-08-27，V56，docs/agents/25「进行中邀约」；
     * 供 dancer 详情组装——舞伴详情页「进行中邀约」卡数据源）。
     * <p>
     * 根因：客人拿到微信返回详情页时"邀约单消失"——本摘要让最近一次邀约
     * （时间/状态/是否被查看/是否履约）在详情页恒可见，邀约从"档案"变"活单"。
     * 未登录（匿名）/无邀约 → null（前端不渲染）；走
     * idx_qwt_demand_records_user_dancer 索引一条轻量查询（用户相关，不入公共缓存）。
     */
    @Transactional(readOnly = true)
    public org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse.RecentDemand
    recentDemandSummary(Long userId, Long dancerId) {
        if (userId == null) {
            return null;
        }
        DemandRecord record = demandRecordRepository
                .findTopByUserIdAndDancerIdOrderByIdDesc(userId, dancerId).orElse(null);
        if (record == null) {
            return null;
        }
        return new org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse.RecentDemand(
                record.getId(),
                record.getCreatedAt(),
                record.getStatus(),
                record.getShareOpenedAt() != null,
                record.getFulfilledAt() != null);
    }

    /** 今日是否已对"任意有门槛舞伴"解锁过联系方式（2026-08-24 每日首免判定；
     * 2026-08-26 V49 起仅当运营开关 dancer.contact.daily.free 开启时被调用）：
     * 取今日全部 DANCER_CONTACT 解锁（target_id = 舞伴 ID），逐个回查门槛——
     * 存在任一有门槛（cost>0 且未软删）即返回 true。无门槛舞伴的免费解锁不消耗
     * 每日首免额度（本来就免费，不应挤占"对有门槛舞伴"的免费机会）。
     */
    private boolean hasGatedContactUnlockToday(Long userId) {
        LocalDateTime since = LocalDate.now().atStartOfDay();
        List<PointsUnlock> todayUnlocks = unlockRepository
                .findByUserIdAndTargetTypeAndCreatedAtGreaterThanEqual(
                        userId, PointsGateTargetType.DANCER_CONTACT, since);
        for (PointsUnlock u : todayUnlocks) {
            PointsGate g = gateRepository
                    .findByTargetTypeAndTargetId(PointsGateTargetType.DANCER_CONTACT, u.getTargetId())
                    .orElse(null);
            if (g != null && !g.isDeleted() && g.getCost() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 联系方式需求落库 + 需求描述生成（2026-08-24 需求 4/5，仅 DANCER_CONTACT；
     * 2026-08-24 晚改版：服务/时间<b>各选 1 项</b>——逼迫用户精准需求，消息前缀
     * 小程序名「去舞厅」；2026-08-25 改版：时间 = <b>具体日期</b>（今天起 7 天窗口，
     * code = YYYY-MM-DD，消息拼接为「M月D日」——替代原相对时间槽「今天/明天/周末」）。
     * <ul>
     *   <li>校验：服务须属于目标舞伴、在用未软删、恰好 1 项；日期须为合法 ISO
     *       LocalDate 且落在 [今天, 今天+6] 窗口（过期/超前 → 1001「所选日期已过期，
     *       请重新选择」）；时长枚举合法；</li>
     *   <li>消息拼接（方案B 结构化三要素，服务端权威文案）：</li>
     * </ul>
     * {@code 去舞厅【服务 · 时间 · 时长 · 位置】}
     * （时长/位置未选时省略；时间 = 「近3天内」相对槽或具体日期；按时段服务 =
     * 类别名 · 具体场景名，2026-08-26；位置 = 舞伴开启「加好友需告知位置」时必填，
     * 2026-08-26——「同城」或「自行前往」，相对关系而非真实地址）。需求记录只存
     * id/日期/整句文案/枚举 code，不存自由文本（隐私克制，见 DemandRecord javadoc）。
     *
     * @return 需求说明详情（含单行验证消息 demandMessage 与多行详细文本
     *         demandDetailText，2026-08-26 21-demand-detail-card；demand 为 null
     *         或非联系方式场景返回 null）——2026-08-26 邀约中转改返回
     *         {@link DemandRecorded}（detail + 落库记录 id + createdAt，
     *         中转分支需 demandId/expireAt）
     * @param status 邀约状态（2026-08-26 邀约中转：中转分支传 DemandStatus.PENDING.name()，
     *               <b>必须落库</b>——管理端待办/防轰炸/24h 降级全部按 status='PENDING'
     *               查询，缺省 null = 不设置（未中转，存量语义等价已获批）；曾因漏传
     *               status 导致待办查不到、防轰炸失效，2026-08-26 修复）
     */
    private DemandRecorded recordDemand(Long userId, Long dancerId,
                                        UnlockRequest.DemandSelection demand, String status) {
        if (demand == null) {
            return null;
        }
        List<Long> serviceIds = demand.serviceIds();
        List<String> timeSlotCodes = demand.timeSlots();
        if (serviceIds == null || serviceIds.isEmpty() || timeSlotCodes == null || timeSlotCodes.isEmpty()) {
            throw new BusinessException(1001, "请选择本次需求的服务与时间");
        }
        if (serviceIds.size() > 1 || timeSlotCodes.size() > 1) {
            throw new BusinessException(1001, "服务与时间请各选 1 项");
        }
        // 2026-08-26 位置表态：仅舞伴开启「加好友需告知位置」时校验（必填 + 枚举合法）；
        // 未开启忽略（向后兼容旧客户端，不校验不落库）
        Dancer dancer = dancerRepository.findById(dancerId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        String location = demand.location();
        if (dancer.isRequireUserLocation()) {
            if (location == null || location.isBlank()) {
                throw new BusinessException(1001, "请选择同城或自行前往");
            }
            UserLocationOption.parse(location); // 非法 → 1001「无效的位置选项」
        } else {
            location = null;
        }
        List<DancerService> services = dancerServiceRepository.findAllById(serviceIds);
        if (services.size() != new HashSet<>(serviceIds).size()) {
            throw new BusinessException(1001, "所选服务不存在");
        }
        // 2026-08-26 子选项：按时段服务须携带 1 个属于其子类别集合的具体场景 code
        // （需求弹层「具体场景」单选，默认 KTV；其余类别忽略本字段）
        String subCategory = demand.subCategory();
        for (DancerService s : services) {
            if (!s.getDancerId().equals(dancerId) || s.isDeleted() || !s.isActive()) {
                throw new BusinessException(1001, "所选服务不属于该舞伴或已下架");
            }
            if (s.getCategory() == DancerServiceCategory.PACKAGE
                    && (subCategory == null || !parseSubCategoryCodes(s.getSubCategory()).contains(subCategory))) {
                throw new BusinessException(1001, "所选服务场景无效，请重新选择");
            }
        }
        // 时间校验：相对槽「近3天内」或 [今天, 今天+6] 具体日期（2026-08-26 相对槽）
        timeSlotCodes.forEach(PointsService::validateDemandTime);
        DemandDuration duration = (demand.duration() == null || demand.duration().isBlank())
                ? null : DemandDuration.parse(demand.duration());
        String message = buildDemandMessage(services.get(0), timeSlotCodes.get(0), subCategory, duration, location);
        DemandRecord record = new DemandRecord();
        record.setUserId(userId);
        record.setDancerId(dancerId);
        record.setServiceIds(serviceIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setTimeSlots(timeSlotCodes.stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setDuration(duration != null ? duration.name() : null);
        record.setUserLocation(location);
        record.setMessage(message);
        record.setStatus(status); // 邀约中转：PENDING；未中转 null（2026-08-26）
        demandRecordRepository.save(record);
        log.info("用户 {} 对舞伴 {} 提出需求：{}", userId, dancerId, message);
        // 2026-08-26 需求说明详情（21-demand-detail-card）：结构化字段供结果卡表格
        // 渲染与离屏 canvas 图片绘制；demandDetailText = 服务端权威多行文本（出口 C
        // 复制即用，前端零拼接）。全部来自本方法上下文，零额外查询。
        DancerService service = services.get(0);
        String timeSlotCode = timeSlotCodes.get(0);
        String timeLabel = DEMAND_TIME_WITHIN_3_DAYS.equals(timeSlotCode)
                ? DEMAND_TIME_WITHIN_3_DAYS_TEXT : DemandDetailTexts.formatDate(LocalDate.parse(timeSlotCode));
        // 2026-08-26 邀约瘦身：详情表述（表格/文本/海报 = 给舞伴看的完整语义，无字数限制）
        // ——时间补「可协商」（近3天内=日期未定需沟通）、位置用 detailText 完整句；单行
        // 验证消息 buildDemandMessage 仍用 display 精简文案（加好友有字数限制），互证不混淆。
        // 2026-08-26 抽公共方法：时间/时长/位置/多行文本全部经 DemandDetailTexts 派生
        // （与 getMyDemand、管理员邀约单详情同源），本方法只补服务部分。
        String timeDetailLabel = DemandDetailTexts.timeDetailLabel(timeSlotCode);
        String durationDetailLabel = DemandDetailTexts.durationLabel(duration != null ? duration.name() : null);
        String locationDetailLabel = DemandDetailTexts.locationLabel(location);
        return new DemandRecorded(new UnlockResponse.DemandDetail(
                dancer.getNickname(),
                dancer.getCity(),
                buildDemandServicePart(service, subCategory),
                service.getPriceText(),
                service.isNegotiable(),
                service.getLocationScope(),
                service.getAdvanceNotice(),
                service.getRules(),
                timeDetailLabel,
                durationDetailLabel,
                locationDetailLabel,
                message,
                DemandDetailTexts.detailText(buildDemandServicePart(service, subCategory),
                        timeDetailLabel, durationDetailLabel, locationDetailLabel)),
                record.getId(), record.getCreatedAt());
    }

    /**
     * 需求落库 + 详情（2026-08-26 修复 NPE 回归：2026-08-26 16:20 生产事故——
     * unlock(DANCER_CONTACT, demand=null) 直接 recordDemand(...).detail() 在
     * demand 为 null（前端无服务降级路径 / mode=view 幂等取回，见 dancer-contact
     * unlockContact(null)）时 NPE → 500）。统一判空：demand 缺失 = 无需求记录，
     * 返回 null（调用点已有 demandDetail != null 判空语义，仅缺判空本身）。
     */
    private UnlockResponse.DemandDetail recordDemandDetail(Long userId, Long dancerId,
                                                           UnlockRequest.DemandSelection demand, String status) {
        DemandRecorded recorded = recordDemand(userId, dancerId, demand, status);
        return recorded != null ? recorded.detail() : null;
    }

    /** 需求落库结果（2026-08-26 邀约中转：detail + 记录 id + createdAt——
     *  中转分支返回 PENDING 需 demandId（去重/详情）与 expireAt（= createdAt + 24h）） */
    private record DemandRecorded(UnlockResponse.DemandDetail detail, Long id, LocalDateTime createdAt) {}

    /**
     * 添加好友需求描述拼接（2026-08-24 方案B 结构化三要素；
     * 2026-08-24 晚：服务/时间各 1 项 + 前缀小程序名「去舞厅」——
     * 舞伴收到好友请求即知来自哪个小程序，降低拒收概率；
     * 2026-08-25：时间 = 具体日期「M月D日」；
     * 2026-08-26：时间支持「近3天内」相对槽；按时段服务部分 = 类别名 · 具体场景名
     * （如「按时段 · KTV」，弹层「具体场景」单选）；位置表态（location，舞伴开启
     * 「加好友需告知位置」时必填——「同城」或「自行前往」，相对关系而非真实地址）；
     * 2026-08-26 晚（用户反馈优化，两轮）：前缀「去舞厅【】」→「💃 舞伴你好～ 在
     * 「去舞厅」看到你，想约你：【】」→（用户嫌寒暄老气）最终定稿
     * 「去舞厅」：【服务 · 时间 · 时长 · 位置】😊——书名号明确小程序名（防名词/动词
     * 歧义）+ 去寒暄 + 结尾 emoji；分隔符统一「 · 」；服务类别词与对外展示一致用
     * 「按时段」（2026-08-27 收敛：验证消息不再用「包时」舞伴行话，统一 V46 合规词，
     * 单一权威派生见 buildDemandServicePart）——
     * 微信加好友验证消息 50 字限制内，实测最长组合约 27 字）：
     * {@code 「去舞厅」：【按时段 · KTV · 近3天内 · 2小时 · 同城】😊}
     * （时长/位置未选时省略）。
     * 2026-08-26（用户反馈定稿落地）：删【】左右括号——微信验证消息输入框的
     * 方括号在部分机型显示拥挤/多余，定稿无括号版：
     * {@code 「去舞厅」：按时段 · KTV · 近3天内 · 2小时 · 同城😊}。
     * 服务名 = 类别权威派生（PACKAGE = 按时段 · 具体场景名；DANCE/ONLINE_CHAT =
     * 类别名；仅 OTHER = admin 手动录入的服务内容）；前端预览规则与本方法一致
     * （注释互证，前端零拼接）。
     */
    private static String buildDemandMessage(DancerService service,
                                             String timeSlotCode,
                                             String subCategoryCode,
                                             DemandDuration duration,
                                             String locationCode) {
        String servicePart = buildDemandServicePart(service, subCategoryCode);
        String timePart = DEMAND_TIME_WITHIN_3_DAYS.equals(timeSlotCode)
                ? DEMAND_TIME_WITHIN_3_DAYS_TEXT : DemandDetailTexts.formatDate(LocalDate.parse(timeSlotCode));
        StringBuilder sb = new StringBuilder("「去舞厅」：")
                .append(servicePart).append(" · ").append(timePart);
        if (duration != null) {
            sb.append(" · ").append(duration.display());
        }
        if (locationCode != null && !locationCode.isBlank()) {
            // 位置已在 recordDemand 校验合法（UserLocationOption.parse），valueOf 安全
            sb.append(" · ").append(UserLocationOption.valueOf(locationCode).display());
        }
        return sb.append("😊").toString();
    }

    /** 需求服务部分（2026-08-26；2026-08-27 收敛：验证消息与对外展示共用本方法——
     *  单一权威派生，见 buildDemandMessage）：按时段 = 类别名 · 具体场景名
     *  （如「按时段 · KTV」，子选项已在 recordDemand 校验属于服务子类别集合，
     *  valueOf 安全）；其余类别 = 服务 label（仅 OTHER 为 admin 手动录入） */
    private static String buildDemandServicePart(DancerService service, String subCategoryCode) {
        if (service.getCategory() == DancerServiceCategory.PACKAGE
                && subCategoryCode != null && !subCategoryCode.isBlank()) {
            DancerServiceSubCategory sub = DancerServiceSubCategory.valueOf(subCategoryCode);
            return service.getCategory().defaultLabel() + " · " + sub.defaultLabel();
        }
        return service.getLabel();
    }

    /** 按时段子类别 code 拆分（逗号连接串 → 列表，去空；空串 = 空列表） */
    private static List<String> parseSubCategoryCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 需求时间 code 校验（2026-08-25 改版：时间 = 具体日期，今天起 7 天；
     * 2026-08-26：新增「近3天内」相对槽 WITHIN_3_DAYS——需求弹层默认时间选项）。
     * code = WITHIN_3_DAYS 直接通过；否则须为 ISO LocalDate（YYYY-MM-DD）且落在
     * [今天, 今天+6] 窗口——非法格式 → 1001「无效的时间选项」；合法但不在窗口 →
     * 1001「所选日期已过期，请重新选择」（弹层跨天提交时前端重新打开选择即可）。
     */
    private static void validateDemandTime(String code) {
        if (DEMAND_TIME_WITHIN_3_DAYS.equals(code)) {
            return;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(code);
        } catch (Exception e) {
            throw new BusinessException(1001, "无效的时间选项");
        }
        LocalDate today = LocalDate.now();
        if (date.isBefore(today) || date.isAfter(today.plusDays(DEMAND_TIME_WINDOW_DAYS - 1))) {
            throw new BusinessException(1001, "所选日期已过期，请重新选择");
        }
    }

    /**
     * 解锁写路径 → 舞伴统计缓存失效（2026-08-21 解锁入统计失效矩阵）。
     * 经 {@link DancerDetailCacheService#invalidate} 唯一入口（级联失效内层
     * DancerStatsService，单一失效入口见其 javadoc）：
     * <ul>
     *   <li>DANCER_CONTACT：target_id = 舞伴 ID 直连；</li>
     *   <li>DANCER_PHOTO：target_id = 照片 ID，回查 dancer_id；</li>
     * </ul>
     * 照片已软删/不存在时跳过（无舞伴可失效）。事务提交后执行（afterCommit），
     * 单元测试无事务时直接内联失效（对齐 DancerService 同款兜底）。
     */
    private void invalidateDancerStatsAfterCommit(PointsGateTargetType targetType, Long targetId) {
        Long dancerId;
        if (targetType == PointsGateTargetType.DANCER_CONTACT) {
            dancerId = targetId;
        } else {
            DancerPhoto photo = dancerPhotoRepository.findByIdAndDeletedFalse(targetId).orElse(null);
            dancerId = photo != null ? photo.getDancerId() : null;
        }
        if (dancerId == null) {
            return;
        }
        Long targetDancerId = dancerId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dancerDetailCacheService.invalidate(targetDancerId);
                }
            });
        } else {
            dancerDetailCacheService.invalidate(targetDancerId);
        }
    }

    /**
     * 目标门槛积分（0 = 无门槛/已清除）——供 dancer 详情组装解锁态（DancerService 调用）。
     */
    @Transactional(readOnly = true)
    public int gateCost(PointsGateTargetType targetType, Long targetId) {
        PointsGate gate = gateRepository.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
        return gate == null || gate.isDeleted() ? 0 : gate.getCost();
    }

    /**
     * 当前用户是否已解锁某目标——供 dancer 详情组装解锁态（DancerService 调用）。
     * 未登录（匿名）恒 false（匿名无账户无解锁记录）。
     */
    @Transactional(readOnly = true)
    public boolean isUnlocked(Long userId, PointsGateTargetType targetType, Long targetId) {
        return userId != null && unlockRepository
                .findByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId).isPresent();
    }

    /**
     * 批量目标门槛积分（targetId → cost，无门槛/已清除的目标缺席——调用方按 0 处理）。
     * 供 dancer 详情一次 IN 查询组装整页照片/联系方式的解锁态（N+1 规避）。
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> gateCosts(PointsGateTargetType targetType, Collection<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (PointsGate gate : gateRepository.findByTargetTypeAndTargetIdInAndDeletedFalse(targetType, targetIds)) {
            result.put(gate.getTargetId(), gate.getCost());
        }
        return result;
    }

    /**
     * 批量当前用户已解锁的目标 ID 集合（供 dancer 详情组装"已解锁"态，N+1 规避）。
     * 未登录（匿名）返回空集（匿名无账户无解锁记录）。
     */
    @Transactional(readOnly = true)
    public Set<Long> unlockedIds(Long userId, PointsGateTargetType targetType, Collection<Long> targetIds) {
        if (userId == null || targetIds == null || targetIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> result = new HashSet<>();
        for (PointsUnlock unlock : unlockRepository
                .findByUserIdAndTargetTypeAndTargetIdIn(userId, targetType, targetIds)) {
            result.add(unlock.getTargetId());
        }
        return result;
    }

    /** 当前余额快照（无账户返回 0——幂等解锁分支用，不触发懒创建写副作用） */
    @Transactional(readOnly = true)
    private long currentBalance(Long userId) {
        PointsAccount account = accountRepository.findByUserId(userId).orElse(null);
        return account != null ? account.getBalance() : 0L;
    }

    /** 门槛目标属主解析：目标存在性 + 归属舞伴（设置门槛资格校验的公共前置） */
    private Dancer resolveGateOwner(PointsGateTargetType targetType, Long targetId) {
        // DANCER_PHOTO / DANCER_VIDEO（2026-08-22 视频门槛）：target_id = qwt_dancer_photos.id
        // （kind 区分媒体类型，门槛目标解析同一张表）
        if (targetType == PointsGateTargetType.DANCER_PHOTO
                || targetType == PointsGateTargetType.DANCER_VIDEO) {
            DancerPhoto photo = dancerPhotoRepository.findByIdAndDeletedFalse(targetId)
                    .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
            return dancerRepository.findByIdAndDeletedFalse(photo.getDancerId())
                    .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        }
        // DANCER_CONTACT：target_id = 舞伴 ID
        return dancerRepository.findByIdAndDeletedFalse(targetId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
    }

    /**
     * 解锁目标解析：目标可见性 + 门槛存在性 + 解锁内容（照片原图 URL / 视频 URL / 联系方式）。
     * <ul>
     *   <li>DANCER_PHOTO / DANCER_VIDEO：媒体须 PUBLIC（未公开对公众不可见）且舞伴 NORMAL；
     *       内容 = 原图/视频 URL（2026-08-22 视频门槛：解锁后返回视频直链供播放）；</li>
     *   <li>DANCER_CONTACT：舞伴须 NORMAL；内容 = 联系方式文本（可能为空串——
     *       舞伴未填联系方式时门槛无意义，防御性返回空）。</li>
     * </ul>
     */
    private UnlockTarget resolveUnlockTarget(PointsGateTargetType targetType, Long targetId) {
        if (targetType == PointsGateTargetType.DANCER_PHOTO
                || targetType == PointsGateTargetType.DANCER_VIDEO) {
            DancerPhoto photo = dancerPhotoRepository.findByIdAndDeletedFalse(targetId)
                    .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
            if (photo.getStatus() != DancerPhotoStatus.PUBLIC) {
                throw new BusinessException(1001, "该媒体暂不可查看");
            }
            Dancer dancer = dancerRepository.findByIdAndDeletedFalse(photo.getDancerId())
                    .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
            if (dancer.getStatus() != DancerStatus.NORMAL) {
                throw new BusinessException(1001, "该舞伴资料暂不可见");
            }
            PointsGate gate = gateRepository.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
            return new UnlockTarget(gate, photo.getUrl(), null, null, false, false);
        }
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(targetId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1001, "该舞伴资料暂不可见");
        }
        PointsGate gate = gateRepository.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
        // 联系方式（2026-08-14 多形态：文本 contact + 图片 contactImageUrl 一并下发，解锁后展示）
        // 2026-08-26 邀约中转（22 号文档）：contactRelay = 开启中转（联系方式把关权交还
        // 舞伴，客人提交邀约后不立即拿微信）；autoRelease = 24h 无回复自动降级策略
        return new UnlockTarget(gate, dancer.getContact(), dancer.getContactImageUrl(), dancer.getCreatedBy(),
                dancer.isContactRelay(), dancer.isAutoRelease());
    }

    /**
     * 本人/管理员归属判定（2026-08-26：舞伴创建者（createdBy 匹配）或平台管理员——
     * 与详情组装 contactUnlocked 的「本人/管理员恒已解锁」语义对齐，解锁豁免依据）。
     */
    private boolean isOwnerOrAdmin(Long userId, Long ownerUserId) {
        if (ownerUserId == null) {
            return false;
        }
        return ownerUserId.equals(userId) || UserContext.getCurrentRole() == UserRole.ADMIN;
    }

    /** 解锁目标解析结果（门槛 + 解锁内容 + 归属用户，一次解析避免重复查询；
     *  contactImageUrl 仅联系方式场景非空；ownerUserId 仅联系方式场景非空 =
     *  舞伴创建者，供本人/管理员归属豁免判定；contactRelay/autoRelease 仅联系
     *  方式场景有意义 = 邀约中转开关与超时降级策略（2026-08-26，22 号文档） */
    private record UnlockTarget(PointsGate gate, String content, String contactImageUrl, Long ownerUserId,
                                boolean contactRelay, boolean autoRelease) {}
}
