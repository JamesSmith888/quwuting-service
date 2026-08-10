package org.quwuting.quwutingservice.venuefeedback.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.config.ReportsProperties;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;

import java.util.Optional;

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

    private VenueFeedbackService service;

    private VenueFeedback feedback;

    @BeforeEach
    void setUp() {
        service = new VenueFeedbackService(venueFeedbackRepository, venueRepository,
                new ReportsProperties(3), pointsService, messageService);
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
