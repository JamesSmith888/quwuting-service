package org.quwuting.quwutingservice.venuepresence.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VisitThanksStatusResponse;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitThankRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店足迹感谢服务（2026-10-13，V50；52 号「到访感谢」节）。
 * <p>
 * 钉住四条不变量：① <b>排除自己</b>（不能感谢自己——贡献者集合先去掉感谢者本人）；
 * ② <b>只在首次感谢时发通知</b>（affected==1，重复点击零消息零新增——幂等由唯一键兜底）；
 * ③ <b>上限截断</b>（防数据异常一次写爆，按最近到访优先）；④ <b>回复文案服务端权威</b>
 * （0/N 两种分态——前端不得自行推导）。
 */
@ExtendWith(MockitoExtension.class)
class VenueVisitThanksServiceTest {

    private static final long VENUE = 121L;
    private static final long ME = 7L;
    private static final List<Long> NO_EXCLUSION = List.of(-1L);

    @Mock
    private VenuePresenceService venuePresenceService;
    @Mock
    private VenueVisitThankRepository thankRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private HeatAccountExclusionService heatAccountExclusionService;

    @InjectMocks
    private VenueVisitThanksService service;

    private final LocalDateTime now = LocalDateTime.now();

    // ── 核心链路 ─────────────────────────────────────────────────────────────

    @Test
    void thankSkipsSelfAndNotifiesOnlyFirstTimePairs() {
        stubVenue();
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(NO_EXCLUSION);
        // 贡献者三人：u1（最近）、u2、以及我自己（必须被排除）
        Map<Long, LocalDateTime> contributors = new HashMap<>(Map.of(
                1L, now.minusHours(2),
                2L, now.minusDays(1),
                ME, now.minusHours(1)));
        when(venuePresenceService.nearbyContributors(VENUE, NO_EXCLUSION)).thenReturn(contributors);
        // u1 首次（affected=1）、u2 已感谢过（affected=2）
        when(thankRepository.thank(eq(VENUE), eq(ME), any(Long.class), any(LocalDateTime.class)))
                .thenReturn(1, 2);
        when(thankRepository.findTargetUserIds(VENUE, ME)).thenReturn(List.of(1L, 2L));
        when(thankRepository.countThankers(VENUE, 1L)).thenReturn(1L);

        VisitThanksResponse resp = service.thank(VENUE, ME);

        assertEquals(1, resp.thankedPeople(), "只计本次新增（幂等：已感谢过的对不重复计）");
        assertEquals(2, resp.contributors(), "可被感谢人数不含自己（三人含我从集合剔除后为 2）");
        assertEquals(0, resp.pendingThanks());
        assertEquals("已感谢 1 位舞友", resp.feedbackText());
        verify(thankRepository, never()).thank(eq(VENUE), eq(ME), eq(ME), any(LocalDateTime.class));
        verify(messageService, times(1)).createOrMergeUnread(
                eq(1L), eq(MessageType.VISIT_THANKED), anyString(), anyString(), eq("VENUE"), eq(VENUE));
        verify(messageService, never()).createOrMergeUnread(
                eq(2L), eq(MessageType.VISIT_THANKED), anyString(), anyString(), eq("VENUE"), eq(VENUE));
    }

    @Test
    void repeatedClickIsIdempotentAndAnswersWithFallbackText() {
        stubVenue();
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(NO_EXCLUSION);
        when(venuePresenceService.nearbyContributors(VENUE, NO_EXCLUSION))
                .thenReturn(new HashMap<>(Map.of(1L, now.minusDays(1))));
        // 已感谢过 ⇒ ON DUPLICATE KEY 吸收（affected=2/0），零新增、零消息
        when(thankRepository.thank(eq(VENUE), eq(ME), eq(1L), any(LocalDateTime.class))).thenReturn(2);
        when(thankRepository.findTargetUserIds(VENUE, ME)).thenReturn(List.of(1L));

        VisitThanksResponse resp = service.thank(VENUE, ME);

        assertEquals(0, resp.thankedPeople());
        assertEquals(0, resp.pendingThanks());
        assertEquals("已经感谢过这里的舞友", resp.feedbackText(),
                "意图已达成只是无新增——回复祝辞而非报错");
        verify(messageService, never()).createOrMergeUnread(any(Long.class), any(), anyString(), anyString(), anyString(), any(Long.class));
    }

    @Test
    void pendingCountsOnlyContributorsNotYetThanked() {
        stubVenue();
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(NO_EXCLUSION);
        when(venuePresenceService.nearbyContributors(VENUE, NO_EXCLUSION))
                .thenReturn(new HashMap<>(Map.of(1L, now.minusDays(1), 2L, now.minusDays(2))));
        // 已感谢过窗外的一位（9L，不在当前集合）——差值语义天然正确，不产生负 pending
        when(thankRepository.findTargetUserIds(VENUE, ME)).thenReturn(List.of(2L, 9L));

        VisitThanksStatusResponse status = service.status(VENUE, ME);

        assertEquals(2, status.contributors());
        assertEquals(1, status.pendingThanks());
    }

    @Test
    void targetsAreCappedKeepingMostRecentVisitors() {
        stubVenue();
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(NO_EXCLUSION);
        Map<Long, LocalDateTime> contributors = new HashMap<>();
        // 60 位贡献者：id 越小越早（最近 = id 最大）
        for (long i = 1; i <= 60; i++) {
            contributors.put(i, now.minusDays(60 - i));
        }
        when(venuePresenceService.nearbyContributors(VENUE, NO_EXCLUSION)).thenReturn(contributors);
        when(thankRepository.thank(eq(VENUE), eq(ME), any(Long.class), any(LocalDateTime.class))).thenReturn(1);
        when(thankRepository.findTargetUserIds(VENUE, ME)).thenReturn(List.of());
        when(thankRepository.countThankers(eq(VENUE), any(Long.class))).thenReturn(1L);

        service.thank(VENUE, ME);

        verify(thankRepository, times(50)).thank(eq(VENUE), eq(ME), any(Long.class), any(LocalDateTime.class));
        // 最近到访优先：最活跃的 60~11 号被感谢，最早的 1~10 号留给下一轮
        verify(thankRepository, never()).thank(eq(VENUE), eq(ME), eq(1L), any(LocalDateTime.class));
        verify(thankRepository, times(1)).thank(eq(VENUE), eq(ME), eq(60L), any(LocalDateTime.class));
    }

    // ── 守卫 ─────────────────────────────────────────────────────────────────

    @Test
    void unknownVenueIsRejectedWithDomainCode() {
        when(venueRepository.findById(VENUE)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.thank(VENUE, ME));

        assertEquals(1022, ex.getCode(), "到访域同码（12 号错误码表）");
    }

    @Test
    void repeatedClicksWithinWindowAreRateLimited() {
        stubVenue();
        when(heatAccountExclusionService.excludedUserIds()).thenReturn(NO_EXCLUSION);
        when(venuePresenceService.nearbyContributors(VENUE, NO_EXCLUSION))
                .thenReturn(new HashMap<>());
        when(thankRepository.findTargetUserIds(VENUE, ME)).thenReturn(List.of());

        service.thank(VENUE, ME);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.thank(VENUE, ME));

        assertEquals(1022, ex.getCode(), "每用户每店 60s 频控（幂等之外的请求风暴保险丝）");
    }

    @Test
    void feedbackTextIsServerAuthoritative() {
        assertEquals("已感谢 3 位舞友", VenueVisitThanksService.feedbackTextOf(3));
        assertEquals("已经感谢过这里的舞友", VenueVisitThanksService.feedbackTextOf(0));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void stubVenue() {
        Venue venue = new Venue();
        venue.setId(VENUE);
        venue.setName("丽莎歌舞厅");
        when(venueRepository.findById(VENUE)).thenReturn(Optional.of(venue));
    }
}
