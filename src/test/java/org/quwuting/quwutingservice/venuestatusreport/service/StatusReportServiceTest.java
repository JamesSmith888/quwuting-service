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
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AnnouncementSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.StatusReportListItem;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatusReportService 上报语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-10「upsert 续期 TTL 失效」根因修复的回归契约
 * （2026-08-11 泛化：renewCreatedAt → renewReport（createdAt + expiresAt 双列直写））：
 * <ol>
 *   <li><b>已有活跃报告再次上报（补充详情）必须续期 createdAt + expiresAt</b>——历史
 *       实现 {@code report.setCreatedAt(now)} 因 {@code @CreationTimestamp} 属性不可变被
 *       Hibernate 静默忽略（HHH000502）；修复后补充路径必须经
 *       {@link StatusReportRepository#renewReport} 直写两列（TTL 唯一事实源 = expires_at
 *       列，2026-08-11 迁移）；</li>
 *   <li><b>新上报（无活跃报告）必须 INSERT 新行</b>（2026-08-20 追加式模型 V34：
 *       每次上报一条新记录，历史多条；被采纳/撤销/过期的旧记录不占活跃唯一槽位——
 *       不再有「恢复软删记录」语义）；</li>
 *   <li><b>新上报（INSERT）不走 renewReport</b>（@CreationTimestamp 在 INSERT 时
 *       正常生成，无需手动续期）。</li>
 * </ol>
 * 2026-08-11 新增守卫契约：非营业店拒绝暂停报（1010）、营业中拒绝恢复报（1012）、
 * 情况不明必填说明（1011）、事件类不受存储态约束。
 * 2026-08-20（V34）：活跃报告定位改用
 * {@code findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc}；
 * 新上报写入口为 {@link StatusReportRepository#insertReport}（部分唯一索引 + ON CONFLICT）。
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
     * 根因回归：已有活跃报告补充详情（更新路径），必须续期 createdAt + expiresAt——
     * renewReport 被调用且时间戳 ≈ now（断言"续期后"而非保留旧值），且<b>不产生新记录</b>
     * （insertReport 不得被调用）。
     */
    @Test
    void submitReport_existingActiveReport_renewsCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusHours(10);
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
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
        verify(statusReportRepository, never()).insertReport(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 2026-08-20 追加式模型（V34）回归：曾被采纳/移除的软删记录<b>不再被恢复</b>——
     * 旧 upsert 模型「撤销/采纳后再次上报 = 恢复软删记录」正是"同用户同门店只有一条
     * 记录不断更新 + 采纳后用户侧仍残留已报告态"的根因。新模型下软删记录不占活跃
     * 唯一槽位（部分唯一索引 WHERE deleted=false AND expires_at > now()），用户再次
     * 上报 = INSERT 新记录（历史多条），不调用 save / renewReport。
     */
    @Test
    void submitReport_softDeletedReport_reportsAsNewRow() {
        // RESUMED 报告仅对声称非营业的门店有意义（1012 守卫对称语义）
        Venue suspended = venue(VENUE_ID);
        suspended.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(suspended));
        StatusReportRepository.ActiveReportStats s = stats(1, LocalDateTime.now());
        when(statusReportRepository.countActiveAndLatestTime(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(s);
        // 无活跃报告（历史软删记录不占活跃槽位，findFirst 返回 empty）
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        service.submitReport(VENUE_ID, new SubmitReportRequest(ReportType.RESUMED, null, null));

        verify(statusReportRepository).insertReport(
                eq(VENUE_ID), eq(USER_ID), eq(ReportType.RESUMED.name()),
                isNull(), any(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(statusReportRepository, never()).save(any());
        verify(statusReportRepository, never()).renewReport(any(Long.class), any(), any());
    }

    /**
     * 新上报（无活跃记录，INSERT 路径）：@CreationTimestamp 在 INSERT 时自动生成
     * createdAt，不应调用 renewReport（批量续期只服务于已有记录的补充/更新路径）。
     */
    @Test
    void submitReport_newReport_doesNotRenewCreatedAt() {
        stubCommon(stats(1, LocalDateTime.now()));
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        verify(statusReportRepository, never()).renewReport(any(Long.class), any(), any());
    }

    /**
     * 2026-08-20 根因回归：新上报必须走确定性原子写入（INSERT ... ON CONFLICT
     * DO NOTHING，部分唯一索引推断），替代「save + catch 23505 + 同事务继续查询」——
     * PG 语句失败后事务中止（25P02），catch 后 getActiveReportSummary 必然 HTTP 500
     * （与 VenueFeedbackService.createFeedback 同源修复，见 15-governance 错误表）。
     */
    @Test
    void submitReport_newReport_usesAtomicInsert() {
        stubCommon(stats(1, LocalDateTime.now()));
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        // 无活跃：先取事务级咨询锁（串行化并发首报），锁内重查确认无活跃后普通 INSERT
        verify(statusReportRepository).lockUserVenue(eq(USER_ID), eq(VENUE_ID));
        verify(statusReportRepository, times(2))
                .findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class));
        verify(statusReportRepository).insertReport(
                eq(VENUE_ID), eq(USER_ID), eq(ReportType.SUSPENDED.name()),
                isNull(), any(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(statusReportRepository, never()).save(any());
    }

    /**
     * 2026-08-20 并发首报竞态契约：锁外查询无活跃 → 加锁 → 锁内重查发现另一请求已
     * 插入活跃记录 → 按补充语义更新该活跃行（save + renewReport），<b>不得重复 INSERT</b>。
     */
    @Test
    void submitReport_concurrentFirstReport_afterLockRecheckUpdatesInsteadOfInsert() {
        stubCommon(stats(1, LocalDateTime.now()));
        LocalDateTime created = LocalDateTime.now().minusMinutes(2);
        // 第一次调用（锁外）= 无活跃；第二次调用（锁内重查）= 另一请求已插入活跃行
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty(), Optional.of(report(200L, false, created)));

        service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        verify(statusReportRepository).lockUserVenue(eq(USER_ID), eq(VENUE_ID));
        verify(statusReportRepository).save(argThat(r -> r.getId().equals(200L)));
        verify(statusReportRepository).renewReport(eq(200L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(statusReportRepository, never()).insertReport(any(), any(), any(), any(), any(), any(), any());
    }

    /** 摘要返回：提交后返回更新后的活跃摘要（activeCount/latestReportTime 透传） */
    @Test
    void submitReport_returnsActiveSummary() {
        LocalDateTime now = LocalDateTime.now();
        stubCommon(stats(3, now));
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.of(report(101L, false, now.minusHours(1))));

        var summary = service.submitReport(VENUE_ID, new SubmitReportRequest(null, null, null));

        assertEquals(3, summary.activeCount());
        assertEquals(now, summary.latestReportTime());
    }

    /**
     * 2026-08-20 补充详情契约：有活跃报告时提交 = 更新该行（type/note 生效）+
     * 续期 TTL，<b>不得 INSERT 新行</b>——完善当前报告不构成新上报。
     */
    @Test
    void submitReport_supplement_updatesActiveRowWithoutNewInsert() {
        stubCommon(stats(1, LocalDateTime.now()));
        LocalDateTime created = LocalDateTime.now().minusHours(2);
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.of(report(102L, false, created)));

        service.submitReport(VENUE_ID,
                new SubmitReportRequest(ReportType.SUDDEN_EVICTION, null, "突然清场，补充说明"));

        verify(statusReportRepository).save(argThat(r ->
                r.getId().equals(102L) && r.getType() == ReportType.SUDDEN_EVICTION
                        && "突然清场，补充说明".equals(r.getNote())));
        verify(statusReportRepository).renewReport(eq(102L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(statusReportRepository, never()).insertReport(any(), any(), any(), any(), any(), any(), any());
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
        verify(statusReportRepository, never())
                .findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyLong(), any(LocalDateTime.class));
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
        verify(statusReportRepository, never())
                .findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyLong(), any(LocalDateTime.class));
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
        verify(statusReportRepository, never())
                .findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyLong(), any(LocalDateTime.class));
    }

    /** 事件类（突然检查）不受存储态约束：非营业门店同样可能突发检查，允许上报 */
    @Test
    void submitReport_eventTypeOnNonOperatingVenue_allowed() {
        Venue suspended = venue(VENUE_ID);
        suspended.setStatus(VenueStatus.SUSPENDED);
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(suspended));
        when(statusReportRepository.findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(USER_ID), eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        StatusReportRepository.ActiveReportStats s = stats(1, LocalDateTime.now());
        when(statusReportRepository.countActiveAndLatestTime(eq(VENUE_ID), any(LocalDateTime.class)))
                .thenReturn(s);

        var summary = service.submitReport(VENUE_ID,
                new SubmitReportRequest(ReportType.SUDDEN_INSPECTION, null, "门口贴了检查通知"));

        assertEquals(1, summary.activeCount());
        verify(statusReportRepository).insertReport(eq(VENUE_ID), eq(USER_ID),
                eq(ReportType.SUDDEN_INSPECTION.name()), isNull(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    // ─── 门店最近突发事件列表（2026-08-12 根因修复：含已过期 + expired 标注；
    //     2026-08-20 移除展示窗口：全量未撤销历史，无 created_at 裁剪） ────────

    /** 构造门店列表行投影 mock（含过期时刻，供 expired 判定观测；admin_action 默认 null = 未处置） */
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
        when(row.getAdminaction()).thenReturn(null);
        when(row.getNickname()).thenReturn(nickname);
        return row;
    }

    /**
     * 根因回归（2026-08-12 + 2026-08-20）：TTL 过期后列表不得消失，且<b>无展示窗口</b>
     * ——「最近的突发事件」= 未撤销的报告事实（活跃 + 已过期 + 超窗历史），已过期行
     * 必须带 expired 标注（与「我的上报记录」active 标注同一语义）。同时校验 mine
     * 高亮与昵称脱敏（首字 + "**"，无昵称「舞友」）。
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
        // 2026-08-20：超出 48h 展示窗口的旧记录同样必须可见（历史视图无时间窗裁剪）
        StatusReportRepository.VenueReportRow staleRow = venueReportRow(
                3L, 5L, "老张", ReportType.SUDDEN_EVICTION,
                now.minusDays(7), now.minusDays(7).plusHours(2));
        when(statusReportRepository.findRecentByVenue(eq(VENUE_ID)))
                .thenReturn(List.of(expiredRow, activeRow, staleRow));

        List<StatusReportListItem> rows = service.listRecentReports(VENUE_ID);

        assertEquals(3, rows.size(), "超窗历史（7 天前）与过期记录必须全部可见，列表无时间窗口");
        StatusReportListItem expired = rows.get(0);
        assertEquals("阿**", expired.reporterName(), "昵称必须脱敏（首字 + **）");
        assertTrue(expired.expired(), "TTL 过期（expires_at < now）的报告必须标注已过期");
        assertTrue(expired.mine(), "当前用户的过期报告仍应标记 mine（高亮「我」）");
        StatusReportListItem active = rows.get(1);
        assertEquals("舞友", active.reporterName(), "无昵称回退「舞友」");
        assertTrue(!active.expired(), "活跃（expires_at > now）报告不得标注已过期");
        assertTrue(!active.mine(), "他人报告不得标记 mine");
        StatusReportListItem stale = rows.get(2);
        assertTrue(stale.expired(), "超窗历史记录（TTL 已过）必须标注已过期");
        assertEquals("老**", stale.reporterName(), "超窗历史同样脱敏展示");
    }

    /**
     * 2026-08-20 修正契约：管理端采纳（admin_action='ADOPTED'）的记录<b>保留展示</b>——
     * 明细列表不得因采纳而消失（采纳 = 处置标记而非删除行），adopted=true 供前端
     * 渲染「已核实」徽标；未处置记录 adopted=false。
     */
    @Test
    void listRecentReports_marksAdoptedButKeepsRow() {
        LocalDateTime now = LocalDateTime.now();
        // 我 2h 前报的暂停营业，1h 前被管理员采纳（未过期、未软删）——必须可见且 adopted=true
        StatusReportRepository.VenueReportRow adoptedRow = venueReportRow(
                11L, USER_ID, "阿明", ReportType.SUSPENDED,
                now.minusHours(2), now.plusHours(2));
        when(adoptedRow.getAdminaction()).thenReturn(AdminAction.ADOPTED.name());
        // 未处置的活跃报告——adopted=false
        StatusReportRepository.VenueReportRow pendingRow = venueReportRow(
                12L, 99L, null, ReportType.SUDDEN_INSPECTION,
                now.minusMinutes(30), now.plusHours(5));
        when(statusReportRepository.findRecentByVenue(eq(VENUE_ID)))
                .thenReturn(List.of(adoptedRow, pendingRow));

        List<StatusReportListItem> rows = service.listRecentReports(VENUE_ID);

        assertEquals(2, rows.size(), "已采纳记录必须保留在明细列表（采纳 ≠ 删除行）");
        StatusReportListItem adopted = rows.get(0);
        assertTrue(adopted.adopted(), "ADOPTED 记录必须标注已核实（adopted=true）");
        assertTrue(!adopted.expired(), "已采纳且未过期记录不得标注已过期");
        StatusReportListItem pending = rows.get(1);
        assertTrue(!pending.adopted(), "未处置记录不得标注已核实");
    }

    /**
     * 2026-08-20 回归：列表查询不得携带任何时间窗口（cutoff）参数——findRecentByVenue
     * 以 venueId 单参数调用，历史全量由 Service 层 limit 上限保护（防无限增长），
     * 禁止 SQL/查询层恢复 created_at 裁剪（历史视图只裁剪"非事实"）。
     */
    @Test
    void listRecentReports_callsRepositoryWithoutTimeWindow() {
        when(statusReportRepository.findRecentByVenue(eq(VENUE_ID)))
                .thenReturn(List.of());

        service.listRecentReports(VENUE_ID);

        verify(statusReportRepository).findRecentByVenue(eq(VENUE_ID));
    }

    // ─── 紧急公告聚合（2026-08-11 新增，2026-08-20 includeExpired 分窗参数化） ──

    /** 构造公告聚合行投影 mock（count / adoptedcnt / latestat 供 Service 组装摘要） */
    private StatusReportRepository.AnnouncementRow announcementRow(ReportType type, Long cnt,
                                                                   Long adoptedCnt, LocalDateTime latestAt) {
        StatusReportRepository.AnnouncementRow row =
                mock(StatusReportRepository.AnnouncementRow.class);
        when(row.getType()).thenReturn(type.name());
        when(row.getCnt()).thenReturn(cnt);
        when(row.getAdoptedcnt()).thenReturn(adoptedCnt);
        when(row.getLatestat()).thenReturn(latestAt);
        return row;
    }

    /** includeExpired 透传契约：公告页（true）与详情页公告条（false）请求同一仓储方法 */
    @Test
    void listAnnouncements_passesIncludeExpiredFlag() {
        when(statusReportRepository.findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(true)))
                .thenReturn(List.of());
        when(statusReportRepository.findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(false)))
                .thenReturn(List.of());

        service.listAnnouncements(VENUE_ID, true);
        service.listAnnouncements(VENUE_ID, false);

        verify(statusReportRepository).findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(true));
        verify(statusReportRepository).findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(false));
    }

    /**
     * 2026-08-20 历史视图回归：includeExpired=true 时，全过期的门店仍返回按类型聚簇
     * 摘要（用户回看社区历史可见），count/adopted/latestAt 组装正确。
     * <p>
     * 2026-08-20 排序修正：<b>时间倒序为主</b>——SUDDEN_INSPECTION（1h 前）比
     * SUSPENDED（8 天前）新，排在前（即使严重级更低）；同时间才退化严重级。
     */
    @Test
    void listAnnouncements_includeExpired_returnsHistoricalSummaries() {
        LocalDateTime now = LocalDateTime.now();
        // 行投影须在外层 when 之前构建（thenReturn 参数求值中 stubbing 其他 mock
        // 会中断外层 stubbing → UnfinishedStubbing，见 listRecentReports 同模式）
        StatusReportRepository.AnnouncementRow suspended =
                announcementRow(ReportType.SUSPENDED, 2L, 1L, now.minusDays(8));
        StatusReportRepository.AnnouncementRow inspection =
                announcementRow(ReportType.SUDDEN_INSPECTION, 1L, 0L, now.minusHours(1));
        when(statusReportRepository.findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(true)))
                .thenReturn(List.of(suspended, inspection));

        List<AnnouncementSummary> rows = service.listAnnouncements(VENUE_ID, true);

        // 时间倒序：SUDDEN_INSPECTION（1h 前）在 SUSPENDED（8 天前）之前
        assertEquals(2, rows.size());
        assertEquals(ReportType.SUDDEN_INSPECTION, rows.get(0).type(), "历史视图含全过期类型摘要，最新信号在前");
        assertEquals(1, rows.get(0).count());
        assertEquals(ReportType.SUSPENDED, rows.get(1).type());
        assertEquals(2, rows.get(1).count());
        assertTrue(rows.get(1).adopted(), "已采纳记录驱动「已核实」标记（过期仍保留事实）");
        assertTrue(!rows.get(0).adopted());
    }

    /**
     * 2026-08-20 排序修正契约（用户实证）：门店先被报暂停营业（已采纳）、随后被报
     * 恢复营业（已采纳）——最新采纳的「恢复营业」代表门店当前事实，即使严重级
     * （recovery）低于「暂停营业」（medium），也必须排在前（详情页公告条/公告页列表
     * 首条都取它，不再错误显示暂停营业）。
     */
    @Test
    void listAnnouncements_sortsByLatestTime_overSeverity() {
        LocalDateTime now = LocalDateTime.now();
        // 暂停营业：2h 前上报并已采纳；恢复营业：30min 前上报并已采纳（最新）
        StatusReportRepository.AnnouncementRow suspended =
                announcementRow(ReportType.SUSPENDED, 1L, 1L, now.minusHours(2));
        StatusReportRepository.AnnouncementRow resumed =
                announcementRow(ReportType.RESUMED, 1L, 1L, now.minusMinutes(30));
        when(statusReportRepository.findAnnouncementsByVenue(eq(VENUE_ID),
                any(LocalDateTime.class), eq(false)))
                .thenReturn(List.of(suspended, resumed));

        List<AnnouncementSummary> rows = service.listAnnouncements(VENUE_ID, false);

        assertEquals(2, rows.size());
        assertEquals(ReportType.RESUMED, rows.get(0).type(),
                "恢复营业（最新）必须排在暂停营业（更旧但严重级更高）之前——状态类信号相互覆盖");
        assertEquals(ReportType.SUSPENDED, rows.get(1).type());
        assertTrue(rows.get(0).adopted() && rows.get(1).adopted());
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
    void removeReport_alreadyDisposed_idempotent() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        // 幂等边界（2026-08-20 修正）：已处置 = admin_action 非空（采纳/移除），
        // 而非 deleted=true——采纳（ADOPTED）不再软删，故已处置判定以 adminAction 为准
        VenueStatusReport report = report(1L, true, LocalDateTime.now().minusHours(1));
        report.setAdminAction(AdminAction.REMOVED);
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
     * 报告<b>仅打采纳标记（不软删，记录保留展示带"已核实"）</b> + 积分奖励 +
     * 处理结果站内信（软关联 VENUE）+ 热度缓存失效——六件事在采纳调用内全部发生。
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
                !r.isDeleted() && r.getAdminAction() == AdminAction.ADOPTED));
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
     * 但仍奖励 + 通知 + 仅打采纳标记（不软删）。
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
     * 但仍仅打采纳标记（不软删）+ 通知（记录保留展示，带"已核实"标注）。
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
     * 匿名上报被采纳：门店状态照常改动 + 报告仅打采纳标记（不软删），但不发分、
     * 不通知（与积分奖励/处理结果站内信同一匿名边界，见 VenueFeedbackService 约定）。
     */
    @Test
    void adoptReport_anonymousReporter_noRewardNoNotifyButVenueUpdated() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        VenueStatusReport report = activeReport(8L, null, false, ReportType.SUSPENDED);
        when(statusReportRepository.findById(8L)).thenReturn(Optional.of(report));

        service.adoptReport(8L);

        verify(venueService).markSuspendedByReport(VENUE_ID, ADMIN_ID);
        verify(statusReportRepository).save(argThat(r -> !r.isDeleted()));
        verify(pointsService, never()).rewardStatusReport(anyLong(), anyLong());
        verify(messageService, never()).create(any(), any(), any(), any(), any(), any());
    }

    /** 已处置（admin_action 非空）报告重复采纳：幂等直接返回，不重复改状态/发分/发信/失效缓存 */
    @Test
    void adoptReport_alreadyDisposed_idempotent() {
        UserContext.set(ADMIN_ID, UserRole.ADMIN);
        // 2026-08-20 修正：幂等边界 = admin_action 非空（采纳不再软删，deleted 不能作为处置判定）
        VenueStatusReport report = activeReport(9L, USER_ID, false, ReportType.SUSPENDED);
        report.setAdminAction(AdminAction.ADOPTED);
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
