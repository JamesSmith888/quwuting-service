package org.quwuting.quwutingservice.venuefeedback.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.config.PointsProperties;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuefeedback.dto.request.CreateFeedbackRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.VenueFeedbackResponse;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VenueFeedbackService 单元测试（Mockito，不依赖数据库）。
 * 覆盖：管理端处理动作（采纳/采纳不奖励/已处理/忽略）→ 上报者处理结果站内信
 * （2026-08-10 新增：同事务、幂等、匿名不通知）；采纳发分联动；终态重复操作幂等。
 */
@ExtendWith(MockitoExtension.class)
class VenueFeedbackServiceTest {

    @Mock
    private VenueFeedbackRepository venueFeedbackRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private PointsService pointsService;
    @Mock
    private MessageService messageService;
    /** 状态类反馈采纳联动（2026-08-20：SUSPENDED/RESUMED 采纳时联动门店营业状态） */
    @Mock
    private VenueService venueService;
    /** 管理端列表批量回填上报者昵称（2026-08-28 补上报者信息；列表场景才消费） */
    @Mock
    private UserRepository userRepository;

    private VenueFeedbackService service;

    private VenueFeedback feedback;

    @BeforeEach
    void setUp() {
        // pointsProperties 传全 0/null 触发 PointsProperties 内部安全回退默认值
        //（2026-08-13 远端合并：VenueFeedbackService 构造器新增 PointsProperties 参数）
        service = new VenueFeedbackService(venueFeedbackRepository, venueRepository,
                new ReportsProperties(3), new PointsProperties(0, 0, 0, 0, null, null),
                pointsService, messageService, venueService, userRepository);
        UserContext.set(99L, UserRole.ADMIN);

        feedback = new VenueFeedback();
        feedback.setId(1L);
        feedback.setVenueId(5L);
        feedback.setUserId(2L);
        feedback.setType(FeedbackType.PRICE);
        feedback.setStatus(ReportStatus.PENDING);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ─── 提交上报的确定性幂等（2026-08-20 根因修复回归） ──────────────────────
    // 契约：登录用户重复提交（60s 冷却外的连点/并发竞态）不得再走「save + catch
    // 23505 + 同事务回查」（PG 事务中止 25P02 → HTTP 500），必须经原子 upsert
    // （INSERT ... ON CONFLICT DO NOTHING）按去重单位幂等返回已有 PENDING 记录。

    private void mockVenueExists() {
        Venue venue = new Venue();
        venue.setId(5L);
        when(venueRepository.findByIdAndDeletedFalse(5L)).thenReturn(Optional.of(venue));
    }

    /** 已存在 PENDING 记录（此前提交过）：upsert DO NOTHING + 回查返回已有记录（幂等 200） */
    @Test
    void createFeedback_loggedInExistingPending_upsertsAndReturnsExistingIdempotently() {
        UserContext.set(2L, UserRole.USER);
        mockVenueExists();
        VenueFeedback pending = new VenueFeedback();
        pending.setId(10L);
        pending.setVenueId(5L);
        pending.setUserId(2L);
        pending.setType(FeedbackType.PRICE);
        pending.setStatus(ReportStatus.PENDING);
        when(venueFeedbackRepository.findByUserIdAndVenueIdAndTypeAndStatus(
                2L, 5L, FeedbackType.PRICE, ReportStatus.PENDING))
                .thenReturn(Optional.of(pending));

        VenueFeedbackResponse resp = service.createFeedback(5L,
                new CreateFeedbackRequest(FeedbackType.PRICE, "补充说明", null, null));

        // 确定性原子 upsert（非纠错场景 field IS NULL 分支），不得走 save + catch 路径；
        // enum 参数传 name()（原生 SQL 绑定 enum 默认 ORDINAL，2026-08-20 根因修复）
        verify(venueFeedbackRepository).upsertPendingWithoutField(
                eq(5L), eq(2L), eq(FeedbackType.PRICE.name()), eq("补充说明"), any(LocalDateTime.class));
        verify(venueFeedbackRepository, never()).save(any());
        assertEquals(10L, resp.id(), "重复提交必须幂等返回已有 PENDING 记录");
    }

    /** 纠错场景（INACCURATE + field）：按 (type, field) 去重单位 upsert + 回查 */
    @Test
    void createFeedback_inaccurateWithField_upsertsWithFieldUnit() {
        UserContext.set(2L, UserRole.USER);
        mockVenueExists();
        VenueFeedback pending = new VenueFeedback();
        pending.setId(12L);
        pending.setVenueId(5L);
        pending.setUserId(2L);
        pending.setType(FeedbackType.INACCURATE);
        pending.setField(FeedbackField.TICKET);
        pending.setStatus(ReportStatus.PENDING);
        when(venueFeedbackRepository.findByUserIdAndVenueIdAndTypeAndFieldAndStatus(
                2L, 5L, FeedbackType.INACCURATE, FeedbackField.TICKET, ReportStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.createFeedback(5L,
                new CreateFeedbackRequest(FeedbackType.INACCURATE, "门票价格有误", FeedbackField.TICKET, "¥30"));

        verify(venueFeedbackRepository).upsertPendingWithField(
                eq(5L), eq(2L), eq(FeedbackType.INACCURATE.name()), eq(FeedbackField.TICKET.name()),
                eq("门票价格有误"), eq("¥30"), any(LocalDateTime.class));
        verify(venueFeedbackRepository, never()).save(any());
    }

    /** 匿名上报：无唯一索引（部分索引仅覆盖 user_id IS NOT NULL），save 原路径，trackable=false */
    @Test
    void createFeedback_anonymous_savesDirectlyWithoutUpsert() {
        UserContext.clear(); // 未登录 → getCurrentUserId() = null
        mockVenueExists();
        VenueFeedback saved = new VenueFeedback();
        saved.setId(11L);
        saved.setVenueId(5L);
        saved.setUserId(null);
        saved.setType(FeedbackType.OTHER);
        saved.setStatus(ReportStatus.PENDING);
        when(venueFeedbackRepository.save(any(VenueFeedback.class))).thenReturn(saved);

        VenueFeedbackResponse resp = service.createFeedback(5L,
                new CreateFeedbackRequest(FeedbackType.OTHER, "匿名补充", null, null));

        verify(venueFeedbackRepository).save(any(VenueFeedback.class));
        verify(venueFeedbackRepository, never()).upsertPendingWithoutField(
                anyLong(), anyLong(), any(), any(), any());
        assertFalse(resp.trackable(), "匿名上报 trackable 必须为 false");
        assertEquals(11L, resp.id());
    }

    /** 场所名称桩（仅真正触发发信的通知路径才需要；匿名/幂等用例不打桩） */
    private void mockVenueName() {
        Venue venue = new Venue();
        venue.setId(5L);
        venue.setName("夜幕舞厅");
        when(venueRepository.findByIdAndDeletedFalse(5L)).thenReturn(Optional.of(venue));
    }

    // ─── 采纳并奖励 → 发分 + 发信 ─────────────────────────────────────────────

    @Test
    void adoptReport_withReward_rewardsPointsAndSendsAdoptedMessage() {
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest(null, true));

        // 状态流转 ADOPTED + 同事务发分
        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED));
        verify(pointsService).rewardFeedback(2L, 1L);
        // 处理结果站内信：FEEDBACK_RESULT，收件人 = 上报者，软关联 VENUE 深链场所详情
        verify(messageService).create(eq(2L), eq(MessageType.FEEDBACK_RESULT),
                eq("上报已采纳"), contains("已被采纳并奖励积分"), eq("VENUE"), eq(5L));
    }

    @Test
    void adoptReport_withNote_appendsAdminNoteToMessage() {
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest("已按纠错内容更新门票价格", true));

        verify(messageService).create(eq(2L), eq(MessageType.FEEDBACK_RESULT),
                eq("上报已采纳"), contains("管理员说明：已按纠错内容更新门票价格"), eq("VENUE"), eq(5L));
    }

    // ─── 采纳不奖励 → 不发分 + 发信（区分奖励语义） ───────────────────────────

    @Test
    void adoptReport_withoutReward_noPointsAndSendsNoRewardMessage() {
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest(null, false));

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED_NO_REWARD));
        verify(pointsService, never()).rewardFeedback(anyLong(), anyLong());
        verify(messageService).create(eq(2L), eq(MessageType.FEEDBACK_RESULT),
                eq("上报已采纳"), contains("未奖励积分"), eq("VENUE"), eq(5L));
    }

    // ─── 已处理 / 已忽略 → 发信 ───────────────────────────────────────────────

    @Test
    void resolveReport_sendsResolvedMessage() {
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.resolveReport(1L, null);

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.RESOLVED));
        verify(messageService).create(eq(2L), eq(MessageType.FEEDBACK_RESULT),
                eq("上报已处理"), contains("上报已处理"), eq("VENUE"), eq(5L));
    }

    @Test
    void dismissReport_sendsDismissedMessage() {
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.dismissReport(1L, null);

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.DISMISSED));
        verify(messageService).create(eq(2L), eq(MessageType.FEEDBACK_RESULT),
                eq("上报已忽略"), contains("上报已忽略"), eq("VENUE"), eq(5L));
    }

    // ─── 状态类采纳联动（2026-08-20：历史/直调 SUSPENDED/RESUMED 反馈采纳时联动门店营业状态） ─

    @Test
    void adoptReport_suspendedStatus_linksVenueMarkSuspended() {
        feedback.setType(FeedbackType.SUSPENDED);
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest(null, true));

        // 状态流转 ADOPTED + 状态类联动（markSuspendedByReport，与 status-reports 采纳同一通道）+ 发分
        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED));
        verify(venueService).markSuspendedByReport(5L, 99L);
        verify(pointsService).rewardFeedback(2L, 1L);
    }

    @Test
    void adoptReport_resumedStatus_linksVenueReopen() {
        feedback.setType(FeedbackType.RESUMED);
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest(null, true));

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED));
        verify(venueService).reopenByReport(5L, 99L);
        verify(pointsService).rewardFeedback(2L, 1L);
    }

    @Test
    void adoptReport_closedDownStatus_noStatusLink() {
        // CLOSED_DOWN 停业认定较重：不自动联动（管理员经 updateVenue 手动执行）
        feedback.setType(FeedbackType.CLOSED_DOWN);
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));
        mockVenueName();

        service.adoptReport(1L, new HandleReportRequest(null, true));

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED));
        verify(venueService, never()).markSuspendedByReport(anyLong(), anyLong());
        verify(venueService, never()).reopenByReport(anyLong(), anyLong());
        verify(pointsService).rewardFeedback(2L, 1L);
    }

    // ─── 幂等：终态重复操作不重复发信 / 发分 ──────────────────────────────────

    @Test
    void handleAgain_onTerminalStatus_idempotentNoMessageNoPoints() {
        feedback.setStatus(ReportStatus.ADOPTED);
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));

        service.adoptReport(1L, new HandleReportRequest(null, true));

        verify(venueFeedbackRepository, never()).save(any());
        verify(pointsService, never()).rewardFeedback(anyLong(), anyLong());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    // ─── 匿名上报：处理结果无法归属，不通知（与积分奖励同一匿名边界） ─────────

    @Test
    void adoptReport_anonymous_noMessageNoPoints() {
        feedback.setUserId(null);
        when(venueFeedbackRepository.findById(1L)).thenReturn(Optional.of(feedback));

        service.adoptReport(1L, new HandleReportRequest(null, true));

        verify(venueFeedbackRepository).save(argThat(f -> f.getStatus() == ReportStatus.ADOPTED));
        verify(pointsService, never()).rewardFeedback(anyLong(), anyLong());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }
}
