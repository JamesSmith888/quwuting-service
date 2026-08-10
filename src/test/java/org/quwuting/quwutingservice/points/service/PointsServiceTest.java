package org.quwuting.quwutingservice.points.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.points.dto.CheckInResponse;
import org.quwuting.quwutingservice.points.dto.PointsSummaryResponse;
import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.quwuting.quwutingservice.points.entity.PointsAccount;
import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsAccountRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private PointsProperties pointsProperties;
    @Mock
    private VenueHeatService venueHeatService;

    private PointsService pointsService;

    @BeforeEach
    void setUp() {
        pointsService = new PointsService(accountRepository, transactionRepository, checkinRepository,
                venueLookupService, dancerRepository, pointsProperties, venueHeatService);
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
}
