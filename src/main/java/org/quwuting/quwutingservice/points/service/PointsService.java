package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PointsProperties pointsProperties;
    private final org.quwuting.quwutingservice.venue.service.VenueHeatService venueHeatService;
    /** 舞伴详情缓存失效入口（2026-08-19：赠送礼物到 DANCER 改变收到积分/收礼聚合、
     *  设置 DANCER_CONTACT 门槛改变联系方式门槛值——经本入口级联失效内层统计缓存，
     *  单一失效入口，见 DancerDetailCacheService javadoc） */
    private final org.quwuting.quwutingservice.dancer.service.DancerDetailCacheService dancerDetailCacheService;

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
     * 校验链：门槛存在（cost>0 且未软删）→ 目标对当前用户可见 → 幂等（已解锁
     * 直接返回内容，不重复扣费）→ 余额 → 原子扣减 → 写 UNLOCK 流水 → 写解锁记录。
     * <p>
     * 并发（2026-08-19 根因修复）：旧实现靠唯一键 (user, target, targetId) 23505 +
     * catch(entityManager.clear()) 兜底并发——但「查幂等 → 扣费 → 写解锁」若交错执行，
     * 后发请求的解锁 INSERT 撞 23505 时，Hibernate flush 失败后事务可能已被标记
     * rollback-only：幂等 200 实际变成 HTTP 500，且「扣费已执行、解锁未落库」的
     * 事务边界完全依赖 JPA 不可靠行为。修复：按 user 粒度 pg_advisory_xact_lock
     * 串行化整个解锁事务（一人同时解锁多目标无真实并发价值，串行正确），使
     * check-then-act 原子化、23505 路径变为不可达（解锁记录仍保留唯一索引为纵深防御）。
     *
     * @return 解锁态 + 解锁后余额 + 解锁内容（照片原图 URL / 联系方式文本）
     */
    @Transactional
    public UnlockResponse unlock(Long userId, PointsGateTargetType targetType, Long targetId) {
        // 目标可见性 + 门槛存在性（同一处解析出内容，避免重复查询）
        UnlockTarget target = resolveUnlockTarget(targetType, targetId);
        PointsGate gate = target.gate();
        if (gate == null || gate.isDeleted()) {
            throw new BusinessException(1001, "该内容无需积分即可查看");
        }
        // 同一用户并发解锁串行化（防「双请求同时通过幂等检查 → 双双扣费」；
        // 锁必须在幂等检查之前获取，见 repository javadoc）
        unlockRepository.lockUserUnlock("unlock:" + userId);
        // 幂等：已解锁 → 直接返回内容（不重复扣费；串行化后此处判定确定可靠）
        if (unlockRepository.findByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId).isPresent()) {
            return new UnlockResponse(true, currentBalance(userId), targetType, targetId, target.content(), target.contactImageUrl());
        }
        int cost = gate.getCost();
        PointsAccount account = getOrCreateAccount(userId);
        if (account.getBalance() < cost) {
            throw new BusinessException(1011, "积分余额不足");
        }
        if (accountRepository.deductBalance(userId, cost) == 0) {
            throw new BusinessException(1011, "积分余额不足");
        }
        long newBalance = account.getBalance() - cost;
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
        PointsTransaction savedTx = transactionRepository.save(tx);
        PointsUnlock unlock = new PointsUnlock();
        unlock.setUserId(userId);
        unlock.setTargetType(targetType);
        unlock.setTargetId(targetId);
        unlock.setTransactionId(savedTx.getId());
        unlockRepository.save(unlock); // 串行化后 23505 不可达；唯一索引仍为纵深防御
        log.info("用户 {} 解锁 {}#{}，消耗 {} 积分（流水 {}）", userId, targetType, targetId, cost, savedTx.getId());
        // 解锁改变舞伴统计输入（unlockStats 累计人次/人数）：真实写入后经事务
        // afterCommit 失效舞伴统计缓存（对齐 DancerViewService 同款边界兜底——
        // 提交后失效保证并发读者回源必读到已提交数据；幂等分支无新数据不需失效）
        invalidateDancerStatsAfterCommit(targetType, targetId);
        return new UnlockResponse(true, newBalance, targetType, targetId, target.content(), target.contactImageUrl());
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
            return new UnlockTarget(gate, photo.getUrl(), null);
        }
        Dancer dancer = dancerRepository.findByIdAndDeletedFalse(targetId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1001, "该舞伴资料暂不可见");
        }
        PointsGate gate = gateRepository.findByTargetTypeAndTargetId(targetType, targetId).orElse(null);
        // 联系方式（2026-08-14 多形态：文本 contact + 图片 contactImageUrl 一并下发，解锁后展示）
        return new UnlockTarget(gate, dancer.getContact(), dancer.getContactImageUrl());
    }

    /** 解锁目标解析结果（门槛 + 解锁内容，一次解析避免重复查询；contactImageUrl 仅联系方式场景非空） */
    private record UnlockTarget(PointsGate gate, String content, String contactImageUrl) {}
}
