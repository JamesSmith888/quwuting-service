package org.quwuting.quwutingservice.venuestatusreport.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AdminStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatusReportService 上报语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-10「upsert 续期 TTL 失效」根因修复的回归契约：
 * <ol>
 *   <li><b>已有报告再次上报必须续期 createdAt</b>——历史实现 {@code report.setCreatedAt(now)}
 *       因 {@code @CreationTimestamp} 属性不可变被 Hibernate 静默忽略（HHH000502），
 *       UPDATE 不含 created_at 列；旧 created_at 超出 4h TTL 窗口后详情页
 *       {@code hasMyStatusReport} 为 false、公开列表（TTL 过滤）查不到 → "刚报告的记录消失"。
 *       修复后 upsert 路径必须经 {@link StatusReportRepository#renewCreatedAt} 直写列
 *       （本测试断言该方法被调用且时间戳为当前时刻，而非旧值）；</li>
 *   <li><b>软删恢复路径同样续期</b>（deleted=true 恢复 = 新报告行为，刷新 TTL）；</li>
 *   <li><b>首次上报（INSERT）不走 renewCreatedAt</b>（@CreationTimestamp 在 INSERT 时
 *       正常生成，无需手动续期）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class StatusReportServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long VENUE_ID = 13L;

    @Mock
    private StatusReportRepository statusReportRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private VenueHeatService venueHeatService;
    @Mock
    private VenueService venueService;
    @Mock
    private PointsService pointsService;
    @Mock
    private MessageService messageService;

    private StatusReportService service;

    @BeforeEach
    void setUp() {
        // entityManager 为 @PersistenceContext 字段注入，不参与构造；测试路径不触发
        // DataIntegrityViolationException 分支，无 entityManager 依赖
        service = new StatusReportService(statusReportRepository, venueRepository, venueHeatService,
                venueService, pointsService, messageService);
        UserContext.set(USER_ID, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Venue venue(Long id) {
        Venue v = new Venue();
        v.setId(id);
        return v;
    }

    /** 构造已有报告实体（含 id，供 upsert 路径观测 renewCreatedAt 的目标） */
    private static VenueStatusReport report(Long id, boolean deleted, LocalDateTime createdAt) {
        VenueStatusReport r = new VenueStatusReport();
        r.setId(id);
        r.setDeleted(deleted);
        r.setCreatedAt(createdAt);
        return r;
    }

    /** 活跃摘要投影 mock（submitReport 返回值的观测点） */
    private StatusReportRepository.ActiveReportStats stats(int activeCount, LocalDateTime latest) {
        StatusReportRepository.ActiveReportStats s =
                mock(StatusReportRepository.ActiveReportStats.class);
        when(s.getActiveCount()).thenReturn((long) activeCount);
        when(s.getLatestTime()).thenReturn(latest);
        return s;
    }

    /** 公共编排：场所存在 + 聚合摘要返回固定值（stats mock 须先独立创建，避免嵌套 stubbing） */
    private void stubCommon(StatusReportRepository.ActiveReportStats stats) {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue(VENUE_ID)));
        when(statusReportRepository.countActiveAndLatestTime(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(stats);
    }

    /**
     * 根因回归：已有活跃报告再次上报（upsert 覆盖），必须续期 createdAt——
     * renewCreatedAt 被调用且时间戳 ≈ now（断言"续期后"而非保留旧值）。
     */
    @Test
    void submitReport_existingActiveReport_renewsCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusHours(10);
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.of(report(99L, false, oldCreatedAt)));

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        // 续期时间戳 ≈ now（renewed），而非保留旧值
        ArgumentCaptor<LocalDateTime> tsCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statusReportRepository).renewCreatedAt(eq(99L), tsCaptor.capture());
        LocalDateTime renewed = tsCaptor.getValue();
        assertTrue(renewed.isAfter(oldCreatedAt),
                "续期后的 createdAt 必须晚于旧值（旧值 " + oldCreatedAt + "，续期 " + renewed + "）");
        assertTrue(Math.abs(java.time.Duration.between(renewed, LocalDateTime.now()).toSeconds()) < 5,
                "续期时间戳必须是当前时刻（而非保留旧 createdAt）");
    }

    /**
     * 软删恢复路径（deleted=true 恢复 = 新报告行为）：同样必须续期 createdAt。
     */
    @Test
    void submitReport_restoreSoftDeletedReport_renewsCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusHours(20);
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.of(report(100L, true, oldCreatedAt)));

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        verify(statusReportRepository).renewCreatedAt(eq(100L), any(LocalDateTime.class));
    }

    /**
     * 首次上报（INSERT 路径）：@CreationTimestamp 在 INSERT 时自动生成 createdAt，
     * 不应调用 renewCreatedAt（批量续期只服务于已有记录的 UPDATE 路径）。
     */
    @Test
    void submitReport_newReport_doesNotRenewCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.empty());

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        verify(statusReportRepository, never()).renewCreatedAt(any(Long.class), any(LocalDateTime.class));
    }

    /** 摘要返回：upsert 后返回更新后的活跃摘要（activeCount/latestReportTime 透传） */
    @Test
    void submitReport_returnsActiveSummary() {
        LocalDateTime now = LocalDateTime.now();
        stubCommon(stats(3, now));
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.of(report(101L, false, now.minusHours(1))));

        var summary = service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        assertEquals(3, summary.activeCount());
        assertEquals(now, summary.latestReportTime());
    }

    // ─── 管理端：列表 / 计数 / 移除（2026-08-10 新增，需 ADMIN） ─────────────────

    private static final Long ADMIN_ID = 99L;

    /** 管理端列表行投影 mock（含上报者身份 + note + 场所名，管理端上下文不做昵称脱敏） */
    private StatusReportRepository.AdminReportRow adminRow(Long id, String nickname, String note) {
        StatusReportRepository.AdminReportRow row =
                mock(StatusReportRepository.AdminReportRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getVenueid()).thenReturn(VENUE_ID);
        when(row.getUserid()).thenReturn(USER_ID);
        when(row.getReason()).thenReturn(ReportReason.CHECK.name());
        when(row.getNote()).thenReturn(note);
        when(row.getOccurredat()).thenReturn(LocalDateTime.now().minusMinutes(5));
        when(row.getCreatedat()).thenReturn(LocalDateTime.now().minusMinutes(1));
        when(row.getVenuename()).thenReturn("夜幕舞厅");
        when(row.getNickname()).thenReturn(nickname);
        return row;
    }

    @Test
    void listAdminReports_returnsUnmaskedReporterAndNote() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        StatusReportRepository.AdminReportRow row = adminRow(1L, "张三", "现场确认已关门");
        when(statusReportRepository.findActiveReports(any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        Page<AdminStatusReportResponse> page = service.listAdminReports(0, 20);

        AdminStatusReportResponse resp = page.getContent().get(0);
        assertEquals("张三", resp.nickname(), "管理端上下文必须返回真实昵称（不脱敏）");
        assertEquals("现场确认已关门", resp.note(), "note 仅管理端可见");
        assertEquals(ReportReason.CHECK, resp.reason());
        assertEquals("夜幕舞厅", resp.venueName());
        assertEquals(USER_ID, resp.userId());
    }

    @Test
    void listAdminReports_nonAdmin_throws() {
        // setUp 已置 USER 角色：非管理员访问管理端列表必须被拒
        assertThrows(BusinessException.class, () -> service.listAdminReports(0, 20));
        verify(statusReportRepository, never()).findActiveReports(any(), any());
    }

    @Test
    void countActiveReports_returnsCount() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        when(statusReportRepository.countActiveReports(any(LocalDateTime.class))).thenReturn(7L);

        assertEquals(7L, service.countActiveReports());
    }

    @Test
    void removeReport_softDeletesAndInvalidatesHeat() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = report(1L, false, LocalDateTime.now().minusHours(1));
        report.setVenueId(VENUE_ID);
        when(statusReportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.removeReport(1L);

        verify(statusReportRepository).save(argThat(r -> r.isDeleted()));
        verify(venueHeatService).invalidate(VENUE_ID);
    }

    @Test
    void removeReport_alreadyDeleted_idempotent() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = report(1L, true, LocalDateTime.now().minusHours(1));
        when(statusReportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.removeReport(1L);

        verify(statusReportRepository, never()).save(any());
        verify(venueHeatService, never()).invalidate(anyLong());
    }

    @Test
    void removeReport_notFound_throws() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        when(statusReportRepository.findById(1L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.removeReport(1L));

        assertEquals(1008, ex.getCode());
    }

    // ─── 管理端采纳（2026-08-10 新增：改状态 + 发分 + 站内信，同事务幂等） ─────────

    /** 构造报告实体（userId 可空 = 匿名上报；deleted 控制活跃/已处置） */
    private static VenueStatusReport activeReport(Long id, Long userId, boolean deleted) {
        VenueStatusReport r = new VenueStatusReport();
        r.setId(id);
        r.setVenueId(VENUE_ID);
        r.setUserId(userId);
        r.setDeleted(deleted);
        r.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return r;
    }

    /**
     * 采纳主链路：门店状态随之改动（VenueService 联动）+ 报告软删 + 积分奖励 +
     * 处理结果站内信（软关联 VENUE）+ 热度缓存失效——五件事在采纳调用内全部发生。
     */
    @Test
    void adoptReport_marksVenueSuspended_rewardsAndNotifiesReporter() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(7L, USER_ID, false);
        when(statusReportRepository.findById(7L)).thenReturn(Optional.of(report));
        Venue venue = venue(VENUE_ID);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));
        when(pointsService.rewardStatusReport(USER_ID, 7L)).thenReturn(5L);

        service.adoptReport(7L);

        verify(venueService).markSuspendedByReport(VENUE_ID, ADMIN_ID);
        verify(statusReportRepository).save(argThat(r -> r.isDeleted()));
        verify(pointsService).rewardStatusReport(USER_ID, 7L);
        verify(messageService).create(eq(USER_ID), eq(MessageType.STATUS_REPORT_RESULT),
                eq("暂停报已采纳"), any(String.class), eq("VENUE"), eq(VENUE_ID));
        verify(venueHeatService).invalidate(VENUE_ID);
    }

    /**
     * 匿名上报被采纳：门店状态照常改动 + 报告软删，但不发分、不通知
     * （与积分奖励/处理结果站内信同一匿名边界，见 VenueFeedbackService 约定）。
     */
    @Test
    void adoptReport_anonymousReporter_noRewardNoNotifyButVenueUpdated() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(8L, null, false);
        when(statusReportRepository.findById(8L)).thenReturn(Optional.of(report));

        service.adoptReport(8L);

        verify(venueService).markSuspendedByReport(VENUE_ID, ADMIN_ID);
        verify(statusReportRepository).save(argThat(r -> r.isDeleted()));
        verify(pointsService, never()).rewardStatusReport(anyLong(), anyLong());
        verify(messageService, never()).create(any(), any(), any(), any(), any(), any());
    }

    /** 已处置（软删）报告重复采纳：幂等直接返回，不重复改状态/发分/发信/失效缓存 */
    @Test
    void adoptReport_alreadyDeleted_idempotent() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(9L, USER_ID, true);
        when(statusReportRepository.findById(9L)).thenReturn(Optional.of(report));

        service.adoptReport(9L);

        verify(venueService, never()).markSuspendedByReport(anyLong(), anyLong());
        verify(statusReportRepository, never()).save(any());
        verify(pointsService, never()).rewardStatusReport(anyLong(), anyLong());
        verify(messageService, never()).create(any(), any(), any(), any(), any(), any());
        verify(venueHeatService, never()).invalidate(anyLong());
    }

    /** 非管理员采纳必须被拒（requireAdmin 先行，不触碰仓储） */
    @Test
    void adoptReport_nonAdmin_throws() {
        assertThrows(BusinessException.class, () -> service.adoptReport(1L));
        verify(statusReportRepository, never()).findById(anyLong());
    }

    @Test
    void adoptReport_notFound_throws() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        when(statusReportRepository.findById(1L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.adoptReport(1L));

        assertEquals(1008, ex.getCode());
    }
}
