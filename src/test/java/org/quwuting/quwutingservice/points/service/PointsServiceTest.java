package org.quwuting.quwutingservice.points.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.service.DancerDetailCacheService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.dto.CheckInResponse;
import org.quwuting.quwutingservice.points.dto.GifterResponse;
import org.quwuting.quwutingservice.points.dto.GiftResponse;
import org.quwuting.quwutingservice.points.dto.PointsSummaryResponse;
import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsGateRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.points.repository.PointsUnlockRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PointsService 账务语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 2026-08-10 生产实证回归：概览接口（readOnly 事务）内嵌"懒创建账户"写副作用，
 * Postgres 抛 "cannot execute INSERT in a read-only transaction"。
 * 本测试锁定<b>读路径零写副作用</b>契约：summary 在无账户时返回零概览且
 * <b>绝不触发 save</b>；账户懒创建只发生在写路径（checkIn 等可写事务）。
 */
@ExtendWith(MockitoExtension.class)
class PointsServiceTest {

    @Mock
    private PointsAccountRepository accountRepository;
    @Mock
    private PointsTransactionRepository transactionRepository;
    @Mock
    private DailyCheckinRepository checkinRepository;
    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private PointsGateRepository gateRepository;
    @Mock
    private PointsUnlockRepository unlockRepository;
    @Mock
    private DancerPhotoRepository dancerPhotoRepository;
    @Mock
    private PointsProperties pointsProperties;
    @Mock
    private VenueHeatService venueHeatService;
    @Mock
    private DancerDetailCacheService dancerDetailCacheService;

    private PointsService pointsService;
    @BeforeEach
    void setUp() {
        pointsService = new PointsService(accountRepository, transactionRepository, checkinRepository,
                gateRepository, unlockRepository, venueLookupService, dancerRepository,
                dancerPhotoRepository, pointsProperties, venueHeatService, dancerDetailCacheService);
        // 各测试按需 stub（Mockito strict stubs：不使用的 stubbing 会在测试级报多余）
    }

    @Test
    void summary_withoutAccount_returnsZeroOverviewWithoutInserting() {
        // 账户不存在（新用户从未打卡/赠送）——概览必须纯只读返回零值，绝不 INSERT
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(transactionRepository.sumEarnedToday(anyLong(), any(), any())).thenReturn(0L);
        when(transactionRepository.sumGiftedToday(anyLong(), any(), any())).thenReturn(0L);
        when(checkinRepository.findByUserIdAndCheckinDate(anyLong(), any())).thenReturn(Optional.empty());

        PointsSummaryResponse resp = pointsService.summary(1L);

        assertEquals(0L, resp.balance(), "无账户时概览余额应为 0（不创建账户）");
        assertEquals(0L, resp.todayEarned());
        assertEquals(0L, resp.todayGifted());
        assertFalse(resp.checkedInToday());
        // 读路径零写副作用：save 必须从未被调用（只读事务内 INSERT 会被 Postgres 拒绝）
        verify(accountRepository, never()).save(any());
    }

    @Test
    void summary_withExistingAccount_returnsBalance() {
        PointsAccount account = new PointsAccount();
        account.setUserId(1L);
        account.setBalance(7L);
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.sumEarnedToday(anyLong(), any(), any())).thenReturn(3L);
        when(transactionRepository.sumGiftedToday(anyLong(), any(), any())).thenReturn(2L);
        when(checkinRepository.findByUserIdAndCheckinDate(anyLong(), any())).thenReturn(Optional.empty());

        PointsSummaryResponse resp = pointsService.summary(1L);

        assertEquals(7L, resp.balance());
        assertEquals(3L, resp.todayEarned());
        assertEquals(2L, resp.todayGifted());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void checkIn_alreadyCheckedIn_returnsIdempotentWithoutEarning() {
        // 今日已打卡（checkin 存在）且已发分（流水幂等键存在）→ 幂等返回，不重复发分
        DailyCheckin checkin = new DailyCheckin();
        checkin.setId(10L);
        checkin.setUserId(1L);
        checkin.setCheckinDate(LocalDate.now());
        when(checkinRepository.findByUserIdAndCheckinDate(1L, LocalDate.now())).thenReturn(Optional.of(checkin));

        PointsAccount account = new PointsAccount();
        account.setUserId(1L);
        account.setBalance(4L);
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByUserIdAndSourceTypeAndSourceId(
                1L, PointsSourceType.DAILY_CHECK_IN, 10L))
                .thenReturn(Optional.of(new PointsTransaction()));

        CheckInResponse resp = pointsService.checkIn(1L);

        assertEquals(false, resp.checkedIn(), "今日已打卡应幂等返回 checkedIn=false");
        assertEquals(0, resp.reward(), "重复打卡不发分");
        assertEquals(4L, resp.balance());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkIn_firstTime_earnsRewardWithIdempotentLedger() {
        // 今日首次打卡：插 checkin → 挣取（幂等流水）
        LocalDate today = LocalDate.now();
        when(pointsProperties.checkInReward()).thenReturn(2);
        when(checkinRepository.findByUserIdAndCheckinDate(1L, today)).thenReturn(Optional.empty());
        DailyCheckin created = new DailyCheckin();
        created.setId(99L);
        when(checkinRepository.save(any())).thenReturn(created);
        when(transactionRepository.findByUserIdAndSourceTypeAndSourceId(
                1L, PointsSourceType.DAILY_CHECK_IN, 99L)).thenReturn(Optional.empty());

        PointsAccount account = new PointsAccount();
        account.setUserId(1L);
        account.setBalance(0L);
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(accountRepository.addBalance(1L, 2L)).thenReturn(1);

        CheckInResponse resp = pointsService.checkIn(1L);

        assertEquals(true, resp.checkedIn(), "首次打卡应返回 checkedIn=true");
        assertEquals(2, resp.reward(), "奖励来自配置（2 分）");
        verify(transactionRepository).saveAndFlush(any());
    }

    // ─── 礼物赠送（2026-08-12 礼物化：载荷 giftCode，价格 GiftCatalog 权威） ───

    /** 未知礼物 code → 1001 业务错误（不落流水、不扣余额） */
    @Test
    void gift_unknownCode_throwsBusinessExceptionWithoutDeducting() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> pointsService.gift(1L, PointsTargetType.DANCER, 2L, "UNKNOWN_GIFT"));

        assertEquals(1001, ex.getCode(), "未知礼物应抛 1001（非 500）");
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).deductBalance(anyLong(), anyLong());
    }

    /**
     * 赠送成功：按 GiftCatalog 权威价格扣减（HEART = 5 分），流水带 gift_code；
     * 响应返回服务端权威余额 + 礼物 code/name。
     */
    @Test
    void gift_success_deductsCatalogPriceAndPersistsGiftCode() {
        // gift(DANCER) 在事务提交后回调 dancerDetailCacheService.invalidate——纯 Mockito 无 Spring 事务，
        // mockStatic 使 registerSynchronization 空操作（缓存失效回调不属于本用例断言范围）
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> tsm =
                     mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
        when(pointsProperties.gift()).thenReturn(new PointsProperties.GiftLimits(10, 20, 5));
        Dancer dancer = new Dancer();
        dancer.setId(2L);
        dancer.setCreatedBy(99L); // 非本人（赠送人 userId=1）
        dancer.setStatus(DancerStatus.NORMAL);
        when(dancerRepository.findByIdAndDeletedFalse(2L)).thenReturn(Optional.of(dancer));
        when(transactionRepository.sumGiftedToday(anyLong(), any(), any())).thenReturn(0L);
        when(transactionRepository.sumGiftedToTargetToday(anyLong(), any(), any(), any(), any())).thenReturn(0L);

        PointsAccount account = new PointsAccount();
        account.setUserId(1L);
        account.setBalance(10L);
        when(accountRepository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(accountRepository.deductBalance(1L, 5L)).thenReturn(1); // HEART price = 5

        PointsTransaction saved = new PointsTransaction();
        saved.setId(77L);
        when(transactionRepository.save(any())).thenReturn(saved);

        GiftResponse resp = pointsService.gift(1L, PointsTargetType.DANCER, 2L, "HEART");

        assertEquals(5L, resp.balance(), "10 - 5(爱心价格) = 5");
        assertEquals("HEART", resp.giftCode());
        assertEquals("爱心", resp.giftName());
        // 流水必须携带 gift_code（"送了什么"语义）
        org.mockito.ArgumentCaptor<PointsTransaction> captor =
                org.mockito.ArgumentCaptor.forClass(PointsTransaction.class);
        verify(transactionRepository).save(captor.capture());
        PointsTransaction tx = captor.getValue();
        assertEquals(-5L, tx.getDelta(), "扣减 = -礼物价格");
        assertEquals("HEART", tx.getGiftCode(), "流水必须记录礼物 code");
        assertEquals(PointsTargetType.DANCER, tx.getTargetType());
        }

    }

    // ─── 礼物赠送者列表（2026-08-12 礼物墙点击弹层） ─────────────────────────

    /** 正常返回：按赠送者聚合映射（昵称/头像/件数/最近赠送时间），无昵称用户回退"舞友"占位 */
    @Test
    void gifters_returnsMappedListWithNicknameFallback() {
        Dancer dancer = new Dancer();
        dancer.setId(7L);
        dancer.setCreatedBy(99L);
        dancer.setStatus(DancerStatus.NORMAL);
        when(dancerRepository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.of(dancer));
        LocalDateTime giftedAt = LocalDateTime.of(2026, 8, 12, 14, 30, 0);
        when(transactionRepository.findGifters(PointsTargetType.DANCER, 7L, "ROSE"))
                .thenReturn(List.of(
                        new Object[]{3L, "小美", "http://avatar/3", 2L, giftedAt},
                        new Object[]{5L, null, null, 1L, giftedAt.minusDays(1)}));

        List<GifterResponse> resp = pointsService.gifters(PointsTargetType.DANCER, 7L, "ROSE");

        assertEquals(2, resp.size(), "按 user 聚合逐行映射");
        assertEquals(3L, resp.get(0).userId());
        assertEquals("小美", resp.get(0).nickname());
        assertEquals("http://avatar/3", resp.get(0).avatarUrl());
        assertEquals(2L, resp.get(0).count());
        assertEquals(giftedAt, resp.get(0).lastGiftedAt(), "最近赠送时间原样下发（前端派生今天/昨天/具体时间）");
        assertEquals("舞友", resp.get(1).nickname(), "无昵称用户回退占位");
        assertEquals(1L, resp.get(1).count());
    }

    /** 未知礼物 code：拒绝（GiftCatalog.fromCode empty → 1001，禁直接 valueOf 抛 500） */
    @Test
    void gifters_unknownGiftCode_throws() {
        assertThrows(BusinessException.class,
                () -> pointsService.gifters(PointsTargetType.VENUE, 1L, "NOT_A_GIFT"));
    }

    /** 目标不可见（PENDING 舞伴）：拒绝——与详情页礼物墙同可见性口径 */
    @Test
    void gifters_invisibleDancer_throws() {
        Dancer dancer = new Dancer();
        dancer.setId(7L);
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.of(dancer));

        assertThrows(BusinessException.class,
                () -> pointsService.gifters(PointsTargetType.DANCER, 7L, "ROSE"));
    }
}
