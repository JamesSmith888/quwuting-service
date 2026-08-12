package org.quwuting.quwutingservice.venuestatusreport.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.config.StatusReportProperties;
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
import org.quwuting.quwutingservice.venuestatusreport.dto.response.StatusReportListItem;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatusReportService 上报语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-10「upsert 续期 TTL 失效」根因修复的回归契约
 * （2026-08-11 泛化：renewCreatedAt → renewReport（createdAt + expiresAt 双列直写））：
 * <ol>
 *   <li><b>已有报告再次上报必须续期 createdAt + expiresAt</b>——历史实现
 *       {@code report.setCreatedAt(now)} 因 {@code @CreationTimestamp} 属性不可变被
 *       Hibernate 静默忽略（HHH000502）；修复后 upsert 路径必须经
 *       {@link StatusReportRepository#renewReport} 直写两列（TTL 唯一事实源 = expires_at
 *       列，2026-08-11 迁移）；</li>
 *   <li><b>软删恢复路径同样续期</b>（deleted=true 恢复 = 新报告行为，刷新 TTL）；</li>
 *   <li><b>首次上报（INSERT）不走 renewReport</b>（@CreationTimestamp 在 INSERT 时
 *       正常生成，无需手动续期）。</li>
 * </ol>
 * 2026-08-11 新增守卫契约：非营业店拒绝暂停报（1010）、营业中拒绝恢复报（1012）、
 * 情况不明必填说明（1011）、事件类不受存储态约束。
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
                venueService, pointsService, messageService, new StatusReportProperties(48));
        UserContext.set(USER_ID, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Venue venue(Long id) {
        Venue v = new Venue();
        v.setId(id);
        // 2026-08-11 非营业守卫：暂停报仅对声称营业（OPEN）门店有意义，
        // 上报测试的场所一律置 OPEN（守卫见 submitReport_nonOperatingVenue_throws）
        v.setStatus(VenueStatus.OPEN);
        return v;
    }

    /** 构造已有报告实体（含 id，供 upsert 路径观测 renewReport 的目标） */
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
     * 根因回归：已有活跃报告再次上报（upsert 覆盖），必须续期 createdAt + expiresAt——
     * renewReport 被调用且时间戳 ≈ now（断言"续期后"而非保留旧值）。
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
        ArgumentCaptor<LocalDateTime> expCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statusReportRepository).renewReport(eq(99L), tsCaptor.capture(), expCaptor.capture());
        LocalDateTime renewed = tsCaptor.getValue();
        assertTrue(renewed.isAfter(oldCreatedAt),
                "续期后的 createdAt 必须晚于旧值（旧值 " + oldCreatedAt + "，续期 " + renewed + "）");
        assertTrue(Math.abs(java.time.Duration.between(renewed, LocalDateTime.now()).toSeconds()) < 5,
                "续期时间戳必须是当前时刻（而非保留旧 createdAt）");
        assertTrue(expCaptor.getValue().isAfter(renewed),
                "expiresAt 必须晚于 createdAt（TTL 窗口，缺省 SUSPENDED = 4h）");
    }

    /**
     * 软删恢复路径（deleted=true 恢复 = 新报告行为）：同样必须续期 createdAt + expiresAt，
     * 且处置标记重置为 null（此前被采纳/移除的软删记录重新变为活跃信号）。
     */
    @Test
    void submitReport_restoreSoftDeletedReport_renewsAndClearsAdminAction() {
        // RESUMED 报告仅对声称非营业的门店有意义（1012 守卫对称语义）
        Venue suspended = venue(VENUE_ID);
        suspended.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(suspended));
        StatusReportRepository.ActiveReportStats s = stats(1, LocalDateTime.now());
        when(statusReportRepository.countActiveAndLatestTime(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(s);
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusHours(20);
        VenueStatusReport existing = report(100L, true, oldCreatedAt);
        existing.setAdminAction(AdminAction.REMOVED);
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.of(existing));

        service.submitReport(VENUE_ID, new SubmitReportRequest(ReportType.RESUMED, null, null));

        verify(statusReportRepository).renewReport(eq(100L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(statusReportRepository).save(argThat(r ->
                !r.isDeleted() && r.getAdminAction() == null && r.getType() == ReportType.RESUMED));
    }

    /**
     * 首次上报（INSERT 路径）：@CreationTimestamp 在 INSERT 时自动生成 createdAt，
     * 不应调用 renewReport（批量续期只服务于已有记录的 UPDATE 路径）。
     */
    @Test
    void submitReport_newReport_doesNotRenewCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.empty());

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        verify(statusReportRepository, never()).renewReport(any(Long.class), any(), any());
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

    /**
     * 2026-08-11 非营业存储态守卫（系统性闭合）：暂停营业报告是"声称营业的门店
     * 实际关门"的实时信号，仅对声称营业（OPEN）的门店有决策意义——门店存储态已
     * 声称非营业（SUSPENDED/CEASED/RENOVATING/CLOSED）时直接拒绝（业务错误 1010），
     * 不消耗限频额度、不触碰报告仓储。前端报告操作状态机已按同一语义把非营业门店
     * chip 翻转为「报告恢复营业」（异步审核通道），本守卫防 API 绕过。
     */
    @Test
    void submitReport_nonOperatingVenue_throws() {
        Venue suspended = venue(VENUE_ID);
        suspended.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(suspended));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null)));

        assertEquals(1010, ex.getCode());
        verify(statusReportRepository, never()).findByUserIdAndVenueId(anyLong(), anyLong());
        verify(statusReportRepository, never()).save(any());
        verify(venueHeatService, never()).invalidate(anyLong());
    }

    /**
     * 2026-08-11 RESUMED 对称守卫：恢复营业报告仅对声称非营业的门店有意义——
     * OPEN 门店报告恢复营业自相矛盾（业务错误 1012）。
     */
    @Test
    void submitReport_resumedOnOpenVenue_throws() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue(VENUE_ID)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitReport(VENUE_ID, new SubmitReportRequest(ReportType.RESUMED, null, null)));
        assertEquals(1012, ex.getCode());
        verify(statusReportRepository, never()).findByUserIdAndVenueId(anyLong(), anyLong());
    }

    /**
     * 2026-08-11 情况不明必填说明守卫：信息量最低、噪音高危——SITUATION_UNCLEAR
     * 提交必须携带补充说明（业务错误 1011），防低信息量噪音刷屏。
     */
    @Test
    void submitReport_situationUnclearWithoutNote_throws() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue(VENUE_ID)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitReport(VENUE_ID,
                        new SubmitReportRequest(ReportType.SITUATION_UNCLEAR, null, null)));
        assertEquals(1011, ex.getCode());
        verify(statusReportRepository, never()).findByUserIdAndVenueId(anyLong(), anyLong());
    }

    /** 事件类（突然检查）不受存储态约束：非营业门店同样可能突发检查，允许上报 */
    @Test
    void submitReport_eventTypeOnNonOperatingVenue_allowed() {
        Venue suspended = venue(VENUE_ID);
        suspended.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(suspended));
        when(statusReportRepository.findByUserIdAndVenueId(USER_ID, VENUE_ID))
                .thenReturn(Optional.empty());
        StatusReportRepository.ActiveReportStats s = stats(1, LocalDateTime.now());
        when(statusReportRepository.countActiveAndLatestTime(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(s);

        var summary = service.submitReport(VENUE_ID,
                new SubmitReportRequest(ReportType.SUDDEN_INSPECTION, null, "门口贴了检查通知"));

        assertEquals(1, summary.activeCount());
    }

    // ─── 门店最近突发事件列表（2026-08-12 根因修复：含已过期 + expired 标注） ────────

    /** 构造门店列表行投影 mock（含过期时刻，供 expired 判定观测） */
    private StatusReportRepository.VenueReportRow venueReportRow(Long id, Long userId, String nickname,
                                                                 ReportType type, LocalDateTime createdAt,
                                                                 LocalDateTime expiresAt) {
        StatusReportRepository.VenueReportRow row =
                mock(StatusReportRepository.VenueReportRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getUserid()).thenReturn(userId);
        when(row.getType()).thenReturn(type.name());
        when(row.getCreatedat()).thenReturn(createdAt);
        when(row.getExpiresat()).thenReturn(expiresAt);
        when(row.getNickname()).thenReturn(nickname);
        return row;
    }

    /**
     * 根因回归（2026-08-12）：TTL 过期后列表不得消失——「最近的突发事件」= 未撤销的
     * 报告事实（活跃 + 已过期），已过期行必须带 expired 标注（与「我的上报记录」
     * active 标注同一语义）。同时校验 mine 高亮与昵称脱敏（首字 + "**"，无昵称「舞友」）。
     */
    @Test
    void listRecentReports_marksExpiredAndActive() {
        LocalDateTime now = LocalDateTime.now();
        // 我 3h 前报的暂停营业，1h 前已过期——必须可见且标 expired + mine
        StatusReportRepository.VenueReportRow expiredRow = venueReportRow(
                1L, USER_ID, "阿明", ReportType.SUSPENDED,
                now.minusHours(3), now.minusHours(1));
        // 别人 30min 前报的突然检查，仍活跃——expired=false
        StatusReportRepository.VenueReportRow activeRow = venueReportRow(
                2L, 99L, null, ReportType.SUDDEN_INSPECTION,
                now.minusMinutes(30), now.plusHours(5));
        when(statusReportRepository.findRecentByVenue(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredRow, activeRow));

        List<StatusReportListItem> rows = service.listRecentReports(VENUE_ID);

        assertEquals(2, rows.size());
        StatusReportListItem expired = rows.get(0);
        assertEquals("阿**", expired.reporterName(), "昵称必须脱敏（首字 + **）");
        assertTrue(expired.expired(), "TTL 过期（expires_at < now）的报告必须标注已过期");
        assertTrue(expired.mine(), "当前用户的过期报告仍应标记 mine（高亮「我」）");
        StatusReportListItem active = rows.get(1);
        assertEquals("舞友", active.reporterName(), "无昵称回退「舞友」");
        assertTrue(!active.expired(), "活跃（expires_at > now）报告不得标注已过期");
        assertTrue(!active.mine(), "他人报告不得标记 mine");
    }

    /** 展示窗口传参契约：cutoff = now - recentHistoryHours（配置化窗口，SQL 层不自定义时间窗） */
    @Test
    void listRecentReports_passesConfiguredCutoff() {
        LocalDateTime now = LocalDateTime.now();
        when(statusReportRepository.findRecentByVenue(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.listRecentReports(VENUE_ID);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statusReportRepository).findRecentByVenue(eq(VENUE_ID), cutoffCaptor.capture());
        // 毫秒级容差（service 内 now 比测试捕获的 now 晚几毫秒）：cutoff ≈ now - 48h
        long diffMillis = Math.abs(
                Duration.between(cutoffCaptor.getValue(), now.minusHours(48)).toMillis());
        assertTrue(diffMillis < 5000, "cutoff 必须 ≈ now - 配置窗口（48h），实际偏差 " + diffMillis + "ms");
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
        when(row.getType()).thenReturn(ReportType.SUDDEN_INSPECTION.name());
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
        when(statusReportRepository.findActiveReports(any(LocalDateTime.class), isNull(),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        when(statusReportRepository.countClustersByVenueAndType(any(LocalDateTime.class)))
                .thenReturn(List.of());

        Page<AdminStatusReportResponse> page = service.listAdminReports(0, 20, null);

        AdminStatusReportResponse resp = page.getContent().get(0);
        assertEquals("张三", resp.nickname(), "管理端上下文必须返回真实昵称（不脱敏）");
        assertEquals("现场确认已关门", resp.note(), "note 仅管理端可见");
        assertEquals(ReportType.SUDDEN_INSPECTION, resp.type());
        assertEquals("突然检查", resp.typeDisplay());
        assertEquals("夜幕舞厅", resp.venueName());
        assertEquals(USER_ID, resp.userId());
    }

    /** 同店同类型聚簇计数回填：管理端「N人报」显示（peerCount = 众报置信度） */
    @Test
    void listAdminReports_peerCountFilledFromCluster() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        StatusReportRepository.AdminReportRow row = adminRow(2L, "李四", null);
        when(statusReportRepository.findActiveReports(any(LocalDateTime.class), isNull(),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        StatusReportRepository.TypeClusterRow cluster =
                mock(StatusReportRepository.TypeClusterRow.class);
        when(cluster.getVenueid()).thenReturn(VENUE_ID);
        when(cluster.getType()).thenReturn(ReportType.SUDDEN_INSPECTION.name());
        when(cluster.getCnt()).thenReturn(3L);
        when(statusReportRepository.countClustersByVenueAndType(any(LocalDateTime.class)))
                .thenReturn(List.of(cluster));

        Page<AdminStatusReportResponse> page = service.listAdminReports(0, 20, null);

        assertEquals(3L, page.getContent().get(0).peerCount());
    }

    @Test
    void listAdminReports_nonAdmin_throws() {
        // setUp 已置 USER 角色：非管理员访问管理端列表必须被拒
        assertThrows(BusinessException.class, () -> service.listAdminReports(0, 20, null));
        verify(statusReportRepository, never()).findActiveReports(any(), any(), any());
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

        verify(statusReportRepository).save(argThat(r ->
                r.isDeleted() && r.getAdminAction() == AdminAction.REMOVED));
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

    // ─── 管理端采纳（2026-08-10 新增：改状态 + 发分 + 站内信，同事务幂等；
    //        2026-08-11 泛化：状态类联动 + 情况不明不奖励） ─────────

    /** 构造报告实体（type 指定；userId 可空 = 匿名上报；deleted 控制活跃/已处置） */
    private static VenueStatusReport activeReport(Long id, Long userId, boolean deleted, ReportType type) {
        VenueStatusReport r = new VenueStatusReport();
        r.setId(id);
        r.setVenueId(VENUE_ID);
        r.setUserId(userId);
        r.setDeleted(deleted);
        r.setType(type);
        r.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return r;
    }

    /**
     * 采纳主链路（SUSPENDED 状态类）：门店状态随之改为暂停营业（VenueService 联动）+
     * 报告软删 + ADOPTED 标记 + 积分奖励 + 处理结果站内信（软关联 VENUE）+
     * 热度缓存失效——六件事在采纳调用内全部发生。
     */
    @Test
    void adoptReport_marksVenueSuspended_rewardsAndNotifiesReporter() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(7L, USER_ID, false, ReportType.SUSPENDED);
        when(statusReportRepository.findById(7L)).thenReturn(Optional.of(report));
        Venue venue = venue(VENUE_ID);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));
        when(pointsService.rewardStatusReport(USER_ID, 7L)).thenReturn(5L);

        service.adoptReport(7L);

        verify(venueService).markSuspendedByReport(VENUE_ID, ADMIN_ID);
        verify(statusReportRepository).save(argThat(r ->
                r.isDeleted() && r.getAdminAction() == AdminAction.ADOPTED));
        verify(pointsService).rewardStatusReport(USER_ID, 7L);
        verify(messageService).create(eq(USER_ID), eq(MessageType.STATUS_REPORT_RESULT),
                eq("突发事件已采纳"), any(String.class), eq("VENUE"), eq(VENUE_ID));
        verify(venueHeatService).invalidate(VENUE_ID);
    }

    /**
     * RESUMED 状态类采纳：门店状态随之改回营业中（reopenByReport，与 markSuspendedByReport
     * 对称），奖励 + 通知（内容含"营业中"结论）。
     */
    @Test
    void adoptReport_resumed_reopensVenueAndRewards() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(70L, USER_ID, false, ReportType.RESUMED);
        when(statusReportRepository.findById(70L)).thenReturn(Optional.of(report));
        Venue venue = venue(VENUE_ID);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));

        service.adoptReport(70L);

        verify(venueService).reopenByReport(VENUE_ID, ADMIN_ID);
        verify(venueService, never()).markSuspendedByReport(anyLong(), anyLong());
        verify(pointsService).rewardStatusReport(USER_ID, 70L);
    }

    /**
     * 事件类采纳：不改门店营业状态（无 markSuspendedByReport / reopenByReport 联动），
     * 但仍奖励 + 通知 + 软删。
     */
    @Test
    void adoptReport_eventType_noVenueStatusChange() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(71L, USER_ID, false, ReportType.SUDDEN_INSPECTION);
        when(statusReportRepository.findById(71L)).thenReturn(Optional.of(report));
        Venue venue = venue(VENUE_ID);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));

        service.adoptReport(71L);

        verify(venueService, never()).markSuspendedByReport(anyLong(), anyLong());
        verify(venueService, never()).reopenByReport(anyLong(), anyLong());
        verify(pointsService).rewardStatusReport(USER_ID, 71L);
    }

    /**
     * 情况不明采纳：不设积分奖励（信息量最低、噪音高危，防价值错配），
     * 但仍软删 + 通知（公告区保留展示至 TTL 过期，带"已核实"标记）。
     */
    @Test
    void adoptReport_situationUnclear_noReward() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(72L, USER_ID, false, ReportType.SITUATION_UNCLEAR);
        when(statusReportRepository.findById(72L)).thenReturn(Optional.of(report));
        Venue venue = venue(VENUE_ID);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));

        service.adoptReport(72L);

        verify(pointsService, never()).rewardStatusReport(anyLong(), anyLong());
        verify(messageService).create(eq(USER_ID), eq(MessageType.STATUS_REPORT_RESULT),
                eq("突发事件已采纳"), any(String.class), eq("VENUE"), eq(VENUE_ID));
    }

    /**
     * 匿名上报被采纳：门店状态照常改动 + 报告软删，但不发分、不通知
     * （与积分奖励/处理结果站内信同一匿名边界，见 VenueFeedbackService 约定）。
     */
    @Test
    void adoptReport_anonymousReporter_noRewardNoNotifyButVenueUpdated() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(8L, null, false, ReportType.SUSPENDED);
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
        VenueStatusReport report = activeReport(9L, USER_ID, true, ReportType.SUSPENDED);
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
