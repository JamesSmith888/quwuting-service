package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceReportResponse;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上报接口的坐标收录（2026-10-08 V46，52 号 / V46 迁移头注）。
 * <p>
 * 分工：{@code VenuePresenceConsentGateTest} 守门禁时序；本类守<b>坐标参数面</b>——
 * 成对校验、域校验、旧端兼容、透传落库。「半套坐标 = 拒收暴露」与「旧端不带坐标 =
 * 照常受理」是两条方向相反的兼容性红线，必须同测。
 */
@ExtendWith(MockitoExtension.class)
class PresenceCoordinateReportTest {

    private static final long USER_ID = 210L;
    private static final long LISA = 121L;
    private static final double LAT = 32.03236;
    private static final double LNG = 120.863716;

    @Mock
    private VenuePresencePingRepository pingRepository;
    @Mock
    private VenuePresenceConsentRepository consentRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private OpsConfigService opsConfigService;

    @InjectMocks
    private VenuePresenceService service;

    @Test
    void coordinatesArePassedThroughToUpsert() {
        givenConsentedVenue();
        ArgumentCaptor<Double> lat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> lng = ArgumentCaptor.forClass(Double.class);

        PresenceReportResponse res = service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, LAT, LNG));

        assertTrue(res.accepted());
        verify(pingRepository).upsertInBucket(eq(USER_ID), eq(LISA), anyLong(), eq(88), eq(65),
                lat.capture(), lng.capture(), any(LocalDateTime.class));
        assertEquals(LAT, lat.getValue());
        assertEquals(LNG, lng.getValue());
    }

    @Test
    void legacyClientWithoutCoordinatesIsStillAccepted() {
        givenConsentedVenue();

        PresenceReportResponse res = service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, null, null));

        assertTrue(res.accepted(), "旧端（不带坐标）必须照常受理——协议向后兼容");
        verify(pingRepository).upsertInBucket(eq(USER_ID), eq(LISA), anyLong(), eq(88), eq(65),
                isNull(), isNull(), any(LocalDateTime.class));
    }

    @Test
    void halfCoordinatesAreRejectedWithoutWriting() {
        givenConsentedVenue();

        assertThrows(BusinessException.class, () -> service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, LAT, null)), "半套坐标 = 端侧 bug，拒收暴露之");
        assertThrows(BusinessException.class, () -> service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, null, LNG)));
        verify(pingRepository, never()).upsertInBucket(any(), any(), anyLong(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        givenConsentedVenue();

        assertThrows(BusinessException.class, () -> service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, 91.0, LNG)));
        assertThrows(BusinessException.class, () -> service.report(LISA, USER_ID,
                new ReportPresenceRequest(88, 65, LAT, 181.0)));
        verify(pingRepository, never()).upsertInBucket(any(), any(), anyLong(), anyInt(),
                any(), any(), any(), any());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void collectSwitchOn() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(true);
    }

    private void givenConsent() {
        VenuePresenceConsent c = new VenuePresenceConsent();
        c.setUserId(USER_ID);
        c.setEnabled(true);
        c.setSource(ConsentSource.PROMPT);
        when(consentRepository.findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(Optional.of(c));
    }

    private void givenConsentedVenue() {
        collectSwitchOn();
        givenConsent();
        Venue lisa = new Venue();
        lisa.setVenueType(VenueType.HALL);
        lisa.setLatitude(32.03236);
        lisa.setLongitude(120.863716);
        when(venueRepository.findById(LISA)).thenReturn(Optional.of(lisa));
    }
}
