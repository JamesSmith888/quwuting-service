package org.quwuting.quwutingservice.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.payqr.enums.PayQrState;
import org.quwuting.quwutingservice.payqr.service.PayQrStatusService;
import org.quwuting.quwutingservice.user.dto.response.AdminUserMarks;
import org.quwuting.quwutingservice.user.service.AdminUserMarkService;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 用户信息标记装配（2026-10-13）：<b>缺席事实必须落成显式状态而不是空</b>——
 * 首版只在 payQrCount&gt;0 时才有东西可显示，新版小程序未发布时用户详情页整页找不到“收款”字样。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserMarkServiceTest {

    @Mock
    private VenuePresenceService venuePresenceService;
    @Mock
    private PayQrStatusService payQrStatusService;

    @InjectMocks
    private AdminUserMarkService service;

    @Test
    void userWithoutAnyPayQrRecordStillGetsAnExplicitNoReportDisplay() {
        when(venuePresenceService.consentStatesFor(anyCollection())).thenReturn(Map.of());
        when(payQrStatusService.snapshotsFor(anyCollection())).thenReturn(Map.of());

        AdminUserMarks marks = service.marksOf(1L);

        assertEquals(0, marks.payQrCount());
        assertEquals(PayQrState.NO_REPORT.name(), marks.payQrState());
        assertFalse(marks.payQrDisplay().isBlank(), "无记录也必须有可读文案，前端据此恒渲染一行");
    }

    @Test
    void configuredAndClearedUsersCarryTheirOwnStateAndDisplay() {
        when(venuePresenceService.consentStatesFor(anyCollection())).thenReturn(Map.of());
        when(payQrStatusService.snapshotsFor(anyCollection())).thenReturn(Map.of(
                2L, new PayQrStatusService.Snapshot(2, PayQrState.CONFIGURED),
                3L, new PayQrStatusService.Snapshot(0, PayQrState.CLEARED)));

        Map<Long, AdminUserMarks> marks = service.marksFor(List.of(2L, 3L));

        assertEquals("CONFIGURED", marks.get(2L).payQrState());
        assertEquals("已配置 2 张", marks.get(2L).payQrDisplay());
        assertEquals("CLEARED", marks.get(3L).payQrState());
    }
}
