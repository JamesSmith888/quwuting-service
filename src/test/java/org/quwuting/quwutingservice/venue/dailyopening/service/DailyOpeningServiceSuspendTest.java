package org.quwuting.quwutingservice.venue.dailyopening.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quwuting.quwutingservice.announcement.service.AnnouncementService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.change.VenueChangePublisher;
import org.quwuting.quwutingservice.venue.change.VenueFactChange;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyVenueSuspendBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.VenueSuspendItemRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchSuspendResult;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.SuspendCityImpact;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.service.VenueStatusGuardService;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量置「暂停营业」的规划 / 熔断 / 预演 / 执行（2026-10-01，Mockito 单测，不连库）。
 * <p>
 * 场景基线：A 城当前营业 10 家，本批要暂停其中 6 家（60% > 默认 50%，且 ≥ 5 家）⇒ 熔断；
 * B 城营业 20 家、暂停 2 家（样本 < 5）⇒ 不熔断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyOpeningServiceSuspendTest {

    @Mock private VenueRepository venueRepository;
    @Mock private VenueStatusLogRepository venueStatusLogRepository;
    @Mock private VenueStatusWatcherService venueStatusWatcherService;
    @Mock private VenueStatusGuardService venueStatusGuardService;
    @Mock private VenueChangePublisher venueChangePublisher;
    @Mock private AnnouncementService announcementService;
    @Mock private OpsConfigService opsConfigService;

    private DailyOpeningService service;
    private final List<Venue> venues = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SuspendBlastRadiusGuard guard = new SuspendBlastRadiusGuard(venueRepository, opsConfigService);
        service = new DailyOpeningService(venueRepository, venueStatusLogRepository, venueStatusWatcherService,
                venueStatusGuardService, venueChangePublisher, guard, announcementService);
        when(opsConfigService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(venueStatusGuardService.decideExternalWrite(any(), any()))
                .thenReturn(new VenueStatusGuardService.Decision(true, null, null));
        for (long id = 1; id <= 6; id++) {
            venues.add(venue(id, "A市"));
        }
        venues.add(venue(101L, "B市"));
        venues.add(venue(102L, "B市"));
        when(venueRepository.findAllById(any())).thenReturn(venues);
        when(venueRepository.countByCityInAndStatus(anyCollection(), eq(VenueStatus.OPEN)))
                .thenReturn(List.of(new Object[]{"A市", 10L}, new Object[]{"B市", 20L}));
    }

    @Test
    void tripsWhenOneCitySuspendsMoreThanAllowedRatio_andWritesNothing() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyBatchSuspend(request(false, null)));
        assertEquals(1036, ex.getCode());
        assertTrue(ex.getMessage().contains("A市 6/10"), ex.getMessage());
        assertFalse(ex.getMessage().contains("B市"), "未熔断的城市不应出现在拒绝原因里");
        verify(venueRepository, never()).save(any());
        verify(venueStatusWatcherService, never()).notifyStatusChanged(any(), any(), any());
        verify(venueChangePublisher, never()).publish(any(), anyCollection());
    }

    @Test
    void dryRunReturnsPlanAndCityImpactsWithoutSideEffects() {
        BatchSuspendResult result = service.applyBatchSuspend(request(true, null));

        assertTrue(result.dryRun());
        assertEquals(0, result.suspended(), "预演不写库，实际暂停数恒 0");
        assertEquals(8, result.details().size(), "details = 将要暂停的计划");
        SuspendCityImpact a = impact(result, "A市");
        assertEquals(6, a.toSuspend());
        assertEquals(10, a.openCount());
        assertEquals(60, a.ratioPercent());
        assertTrue(a.tripped());
        assertFalse(impact(result, "B市").tripped(), "样本不足 min_count 的城市不熔断");
        verify(venueRepository, never()).save(any());
        verify(venueStatusLogRepository, never()).save(any());
        verify(venueStatusGuardService, never()).takeOverByExternalWrite(any());
        verify(venueChangePublisher, never()).publish(any(), anyCollection());
    }

    @Test
    void confirmedCityPassesAndBatchIsAppliedWithOneChangeEvent() {
        BatchSuspendResult result = service.applyBatchSuspend(request(false, List.of("A市")));

        assertFalse(result.dryRun());
        assertEquals(8, result.suspended());
        assertTrue(impact(result, "A市").confirmed());
        verify(venueRepository, times(8)).save(any());
        verify(venueStatusWatcherService, times(8)).notifyStatusChanged(any(), eq(VenueStatus.OPEN), eq(VenueStatus.SUSPENDED));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(venueChangePublisher, times(1)).publish(eq(VenueFactChange.STATUS), ids.capture());
        assertEquals(8, ids.getValue().size(), "整批一次声明变更，携带全部门店");
        assertTrue(venues.stream().allMatch(v -> v.getStatus() == VenueStatus.SUSPENDED));
    }

    @Test
    void lockedVenuesAreExcludedFromBlastRadius() {
        // A 城 6 家里有 2 家人工锁内：真正会被暂停的只有 4 家（< 5）⇒ 不熔断
        when(venueStatusGuardService.decideExternalWrite(any(), any())).thenAnswer(inv -> {
            Venue v = inv.getArgument(0);
            return v.getId() <= 2
                    ? new VenueStatusGuardService.Decision(false,
                    org.quwuting.quwutingservice.venue.dailyopening.enums.GuardSkipReason.LOCKED, null)
                    : new VenueStatusGuardService.Decision(true, null, null);
        });
        BatchSuspendResult result = service.applyBatchSuspend(request(false, null));
        assertEquals(6, result.suspended());
        assertEquals(2, result.skippedLocked());
        assertFalse(impact(result, "A市").tripped(), "门禁跳过的门店不计入熔断分子");
    }

    private static SuspendCityImpact impact(BatchSuspendResult result, String city) {
        return result.cityImpacts().stream().filter(i -> i.city().equals(city)).findFirst().orElseThrow();
    }

    private ApplyVenueSuspendBatchRequest request(boolean dryRun, List<String> confirmed) {
        List<VenueSuspendItemRequest> items = venues.stream()
                .map(v -> new VenueSuspendItemRequest(v.getId(), LocalDate.of(2026, 10, 1), "test-source", "AGENT_BATCH"))
                .toList();
        return new ApplyVenueSuspendBatchRequest(items, dryRun, confirmed);
    }

    private static Venue venue(long id, String city) {
        Venue v = new Venue();
        v.setId(id);
        v.setName("店" + id);
        v.setCity(city);
        v.setStatus(VenueStatus.OPEN);
        return v;
    }
}
