package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserConsentResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitsResponse;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.enums.PresenceConsentState;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * admin 用户足迹卡的「开关当前态 + 变更流水」（2026-10-07，52 号 §5 / §6.3）。
 * <p>
 * 与 {@code VenuePresenceConsentGateTest} 的分工：那边验「门禁收不收」，这边验
 * 「admin 看到的与门禁同源」——判据单点 {@code consentStateOf} 是两者的唯一交点。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserConsentDisplayTest {

    private static final long USER_ID = 2L;

    @Mock
    private VenuePresencePingRepository pingRepository;
    @Mock
    private VenuePresenceConsentRepository consentRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OpsConfigService opsConfigService;

    @InjectMocks
    private VenuePresenceService service;

    // ── 判据单点 ────────────────────────────────────────────────────────────

    @Test
    void stateDerivationKeepsLegacyDefaultOutOfEnabled() {
        assertEquals(PresenceConsentState.ENABLED,
                VenuePresenceService.consentStateOf(true, ConsentSource.PROMPT));
        assertEquals(PresenceConsentState.ENABLED,
                VenuePresenceService.consentStateOf(true, ConsentSource.USER));
        assertEquals(PresenceConsentState.DISABLED,
                VenuePresenceService.consentStateOf(false, ConsentSource.PROMPT));
        assertEquals(PresenceConsentState.DISABLED,
                VenuePresenceService.consentStateOf(false, ConsentSource.DEFAULT));
        assertEquals(PresenceConsentState.PENDING_PROMPT,
                VenuePresenceService.consentStateOf(true, ConsentSource.DEFAULT),
                "默认开启期补记的行 enabled=true，但用户从未被询问，不能读成已授权");
        assertEquals(PresenceConsentState.NEVER_ASKED,
                VenuePresenceService.consentStateOf(null, null));
    }

    // ── 当前态与流水 ────────────────────────────────────────────────────────

    @Test
    void enabledUserExposesCurrentStateAndReversedHistory() {
        givenUserExists();
        givenOpsSwitch(true);
        givenHistory(
                consent(true, ConsentSource.USER, 300),
                consent(true, ConsentSource.PROMPT, 100),
                consent(true, ConsentSource.DEFAULT, 200));

        AdminUserConsentResponse c = consentOf();

        assertEquals("ENABLED", c.state());
        assertEquals(ConsentSource.USER.name(), c.source());
        assertTrue(c.enabled());
        assertFalse(c.historyTruncated());
        assertEquals(3, c.history().size());
        // 倒序：首行 = 当前态（USER 300 最新），DEFAULT 行保留在末尾可见
        assertEquals(ConsentSource.USER.name(), c.history().get(0).source());
        assertEquals("PENDING_PROMPT", c.history().get(2).state(),
                "历史 DEFAULT 行必须如实呈现为「待补问」，不能被翻译成已开启");
        assertTrue(c.opsCollectEnabled());
    }

    @Test
    void neverAskedUserGetsExplicitStateInsteadOfNullFields() {
        givenUserExists();
        givenOpsSwitch(true);
        givenHistory();

        AdminUserConsentResponse c = consentOf();

        assertEquals("NEVER_ASKED", c.state());
        assertNull(c.enabled(), "从未确立 = 无行，enabled 恒 null（不是 false）");
        assertNull(c.source());
        assertNull(c.lastChangedAt());
        assertTrue(c.history().isEmpty());
        assertFalse(c.historyTruncated());
    }

    @Test
    void opsSwitchOffIsSurfacedSoBlankFootprintIsNotBlamedOnTheUser() {
        givenUserExists();
        givenOpsSwitch(false);
        givenHistory(consent(true, ConsentSource.PROMPT, 100));

        AdminUserConsentResponse c = consentOf();

        assertTrue(c.enabled(), "用户态仍是已开启");
        assertFalse(c.opsCollectEnabled(),
                "全站停采必须下发，否则运营会把「没有新数据」误读成「他关了采集」");
    }

    @Test
    void historyTruncationIsExplicitAndNeverSilent() {
        givenUserExists();
        givenOpsSwitch(true);
        when(consentRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(USER_ID), any()))
                .thenReturn(List.of(consent(true, ConsentSource.PROMPT, 100)));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        consentOf();

        // 上限 + 1：多取一条只用于判断是否超限，避免「恰好 N 条」被误标为截断
        verify(consentRepository).findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(
                eq(USER_ID), pageable.capture());
        assertEquals(51, pageable.getValue().getPageSize());
    }

    @Test
    void unknownUserFailsBeforeTouchingConsentOrPings() {
        when(userRepository.findByIdAndDeletedFalse(USER_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.visitsFor(USER_ID, 90));
        verify(consentRepository, never())
                .findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(any(), any());
        verify(pingRepository, never()).findHitsByUserIdSince(any(), any(), anyInt(), anyInt());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void givenUserExists() {
        User u = new User();
        u.setId(USER_ID);
        when(userRepository.findByIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(u));
        // 空足迹：足迹本身不是本测试的被测对象，走短路分支（⛔ 空集合进原生 IN () 是语法错误）
        when(pingRepository.findHitsByUserIdSince(eq(USER_ID), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
    }

    private void givenOpsSwitch(boolean enabled) {
        when(opsConfigService.isEnabled(anyString(), anyBoolean())).thenReturn(enabled);
    }

    private void givenHistory(VenuePresenceConsent... rows) {
        when(consentRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(USER_ID), any()))
                .thenReturn(List.of(rows));
    }

    private static VenuePresenceConsent consent(boolean enabled, ConsentSource source,
                                                int minutesAgo) {
        VenuePresenceConsent c = new VenuePresenceConsent();
        c.setUserId(USER_ID);
        c.setEnabled(enabled);
        c.setSource(source);
        c.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return c;
    }

    /** 跑一次完整足迹查询并取其 consent 段（口径与 HTTP 层一致，不做二次拼装）。 */
    private AdminUserConsentResponse consentOf() {
        AdminUserVisitsResponse res = service.visitsFor(USER_ID, 90);
        assertEquals(Long.valueOf(USER_ID), res.consent().userId());
        return res.consent();
    }
}
