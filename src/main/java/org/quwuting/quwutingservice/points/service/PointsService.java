package org.quwuting.quwutingservice.points.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.db.DbConstraintViolations;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.dto.*;
import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.GiftCatalog;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        };
    }

    /** 合规规则文案（后端下发唯一事实源，前端直接渲染——禁前端硬编码）。
     *  2026-08-12 礼物化：积分退化为"获取礼物的代币"，赠送语义 = 购买礼物并送出
     *  （一次性表达，不可回收、不可再流转——彻底消除资产转移语义，见 AGENTS.md）。 */
    private static final String RULES_TEXT =
            "积分为社区贡献值，可通过每日打卡、提交信息反馈（经管理员采纳）等免费获得；"
                    + "积分用于购买礼物送给门店/舞伴，表达支持。"
                    + "礼物不具备任何货币属性，不可提现、不可转让、不可兑换任何实物或服务。";

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
    private final VenueLookupService venueLookupService;
    private final DancerRepository dancerRepository;
    private final PointsProperties pointsProperties;
    private final org.quwuting.quwutingservice.venue.service.VenueHeatService venueHeatService;

    @PersistenceContext
    private EntityManager entityManager;

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
     */
    @Transactional
    public CheckInResponse checkIn(Long userId) {
        int reward = pointsProperties.checkInReward();
        LocalDate today = LocalDate.now();
        DailyCheckin checkin = checkinRepository.findByUserIdAndCheckinDate(userId, today)
                .orElseGet(() -> {
                    DailyCheckin c = new DailyCheckin();
                    c.setUserId(userId);
                    c.setCheckinDate(today);
                    try {
                        return checkinRepository.save(c);
                    } catch (DataIntegrityViolationException e) {
                        if (!DbConstraintViolations.isUniqueViolation(e)) {
                            throw e;
                        }
                        // 并发竞态：另一请求已打卡，幂等视为已打卡（flush 后回查）
                        entityManager.clear();
                        return checkinRepository.findByUserIdAndCheckinDate(userId, today)
                                .orElseThrow(() -> new IllegalStateException(
                                        "打卡唯一索引冲突但未找到记录: userId=" + userId));
                    }
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
        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(userId);
        tx.setDelta(delta);
        tx.setBalanceAfter(newBalance);
        tx.setSourceType(sourceType);
        tx.setSourceId(sourceId);
        tx.setRemark(remark);
        try {
            transactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            if (!DbConstraintViolations.isUniqueViolation(e)) {
                throw e;
            }
            // 重复发放竞态：唯一键冲突 = 已有同来源流水，幂等返回该来源的余额快照
            // （本事务因异常整体回滚，上面 addBalance 的累加一并回滚，无副作用）
            entityManager.clear();
            PointsTransaction existing = transactionRepository
                    .findByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "积分发放唯一键冲突但未找到记录: userId=" + userId
                                    + ", sourceType=" + sourceType + ", sourceId=" + sourceId));
            return existing.getBalanceAfter();
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
}
