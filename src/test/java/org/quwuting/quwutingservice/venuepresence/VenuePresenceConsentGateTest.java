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
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 到店足迹同意模型（2026-10-03 五轮「到店首问」，52 号 §5）：服务端门禁、来源解析、统计口径同一判据。
 * <p>
 * 现网背景：09-29 ~ 10-03「默认开启」期间 11 位用户未经询问即被记录（consent 行 source=DEFAULT）。
 * 门禁上线后这批用户的 ping 一律拒收，直到他们在下次到店时回答首问。
 */
@ExtendWith(MockitoExtension.class)
class VenuePresenceConsentGateTest {

    private static final long USER_ID = 2L;
    private static final long LISA = 121L;

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

    /** 旧端形态（不带坐标）：V46 增坐标后仍须正常受理（协议向后兼容） */
    private static final ReportPresenceRequest AT_LISA = new ReportPresenceRequest(88, 65, null, null);

    @Test
    void neverAskedUserIsRejectedWithoutWriting() {
        collectSwitchOn();
        when(consentRepository.findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(Optional.empty());

        PresenceReportResponse res = service.report(LISA, USER_ID, AT_LISA);

        assertFalse(res.accepted());
        assertEquals(PresenceReportResponse.REASON_CONSENT_REQUIRED, res.reason());
        verifyNoPingWritten();
    }

    @Test
    void legacyDefaultOnIsNotConsent() {
        collectSwitchOn();
        latestConsent(true, ConsentSource.DEFAULT);

        PresenceReportResponse res = service.report(LISA, USER_ID, AT_LISA);

        assertEquals(PresenceReportResponse.REASON_CONSENT_REQUIRED, res.reason(),
                "默认开启期补记的 DEFAULT 行记录的是「没问过」，不是同意");
        verifyNoPingWritten();
    }

    @Test
    void explicitlyDisabledUserIsRejected() {
        collectSwitchOn();
        latestConsent(false, ConsentSource.PROMPT);

        assertFalse(service.report(LISA, USER_ID, AT_LISA).accepted());
        verifyNoPingWritten();
    }

    @Test
    void promptAllowedUserIsAccepted() {
        collectSwitchOn();
        latestConsent(true, ConsentSource.PROMPT);
        Venue lisa = new Venue();
        lisa.setVenueType(VenueType.HALL);
        lisa.setLatitude(32.032494);
        lisa.setLongitude(120.864715);
        when(venueRepository.findById(LISA)).thenReturn(Optional.of(lisa));

        PresenceReportResponse res = service.report(LISA, USER_ID, AT_LISA);

        assertTrue(res.accepted());
        verify(pingRepository).upsertInBucket(eq(USER_ID), eq(LISA), anyLong(), eq(88), eq(65), isNull(), isNull(), any());
    }

    @Test
    void reportedSourceDefaultsToUserForLegacyClientsAndAcceptsPrompt() {
        ArgumentCaptor<VenuePresenceConsent> saved = ArgumentCaptor.forClass(VenuePresenceConsent.class);

        service.recordConsent(USER_ID, false, null);
        service.recordConsent(USER_ID, true, "prompt");

        verify(consentRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(ConsentSource.USER, saved.getAllValues().get(0).getSource(), "10-03 前的端只在设置页上报且不带来源");
        assertEquals(ConsentSource.PROMPT, saved.getAllValues().get(1).getSource());
        assertTrue(saved.getAllValues().get(1).getEnabled());
    }

    @Test
    void clientCannotClaimDefaultOrUnknownSource() {
        assertThrows(BusinessException.class, () -> service.recordConsent(USER_ID, true, "DEFAULT"));
        assertThrows(BusinessException.class, () -> service.recordConsent(USER_ID, true, "AUTO"));
        assertThrows(BusinessException.class, () -> service.recordConsent(USER_ID, null, "PROMPT"));
        verify(consentRepository, never()).save(any());
    }

    @Test
    void statsSplitLegacyDefaultFromExplicitConsentWithTheGatePredicate() {
        when(consentRepository.countLatestByEnabledAndSource()).thenReturn(List.of(
                new Object[]{true, "DEFAULT", 11L},
                new Object[]{false, "USER", 9L},
                new Object[]{true, "USER", 2L},
                new Object[]{true, "PROMPT", 3L},
                new Object[]{false, "PROMPT", 1L}));
        when(consentRepository.countPromptAnswersByEnabled()).thenReturn(List.of(
                new Object[]{true, 3L}, new Object[]{false, 1L}));
        when(consentRepository.countUserChangesSince(any())).thenReturn(36L);

        VenuePresenceConsentStats stats = service.consentStats();

        assertEquals(5L, stats.enabledUsers(), "已允许 = 显式开启（USER + PROMPT），与门禁同一判据");
        assertEquals(10L, stats.disabledUsers());
        assertEquals(11L, stats.legacyDefaultUsers(), "默认开启期未经询问者单列为待补问，不计入已允许");
        assertEquals(3L, stats.promptAllowedUsers());
        assertEquals(1L, stats.promptDeclinedUsers());
        assertEquals(36L, stats.changes30d());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void collectSwitchOn() {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(true);
    }

    private void latestConsent(boolean enabled, ConsentSource source) {
        VenuePresenceConsent c = new VenuePresenceConsent();
        c.setUserId(USER_ID);
        c.setEnabled(enabled);
        c.setSource(source);
        when(consentRepository.findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(USER_ID))
                .thenReturn(Optional.of(c));
    }

    private void verifyNoPingWritten() {
        verify(pingRepository, never()).upsertInBucket(any(), any(), anyLong(), anyInt(), any(), any(), any(), any());
        verify(venueRepository, never()).findById(any());
    }
}
