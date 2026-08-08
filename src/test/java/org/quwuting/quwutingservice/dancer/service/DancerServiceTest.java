package org.quwuting.quwutingservice.dancer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.dto.request.CreateDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse;
import org.quwuting.quwutingservice.dancer.dto.response.RecognizeResponse;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DancerService 单元测试（Mockito，不依赖数据库）。
 * 覆盖：创建（PENDING/NORMAL/清洗/常驻舞厅关联）、可见性规则（NORMAL 公开 /
 * PENDING/HIDDEN/REJECTED 仅创建人+管理员）、认可每日一记 toggle（插入+标签 / 取消+级联删标签）、
 * 标签字典校验、认可目标可见性、管理端状态切换（通过/驳回/隐藏 → 站内信通知创建人）。
 */
@ExtendWith(MockitoExtension.class)
class DancerServiceTest {

    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private DancerVenueRepository dancerVenueRepository;
    @Mock
    private DancerRecognitionRepository recognitionRepository;
    @Mock
    private DancerRecognitionTagRepository recognitionTagRepository;
    @Mock
    private DancerAggregateService aggregateService;
    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private MessageService messageService;

    private DancerService dancerService;

    private Dancer dancer;

    @BeforeEach
    void setUp() {
        dancerService = new DancerService(dancerRepository, dancerVenueRepository, recognitionRepository,
                recognitionTagRepository, aggregateService, venueLookupService, messageService);

        dancer = new Dancer();
        dancer.setId(1L);
        dancer.setNickname("小雅");
        dancer.setCreatedBy(1L);
        dancer.setStatus(DancerStatus.NORMAL);
    }

    // ─── 创建 ───────────────────────────────────────────────────────────────

    @Test
    void createDancer_userRegistration_defaultsToPending() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> {
            Dancer d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        Long id = dancerService.createDancer(2L,
                new CreateDancerRequest("  小雅  ", null, " 舞姿优秀 ", null, "杭州", null), false);

        assertEquals(10L, id);
        verify(dancerRepository).save(argThat(d ->
                d.getNickname().equals("小雅")           // 首尾空白被清洗
                && d.getBio().equals("舞姿优秀")
                && d.getCity().equals("杭州")
                && d.getStatus() == DancerStatus.PENDING   // 主动注册默认审核中（隐私第一道闸）
                && d.getCreatedBy().equals(2L)));
    }

    @Test
    void createDancer_adminCreatesNormal() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> inv.getArgument(0));

        dancerService.createDancer(99L, new CreateDancerRequest("后台舞伴", null, null, null, null, null), true);

        verify(dancerRepository).save(argThat(d ->
                d.getStatus() == DancerStatus.NORMAL && d.getCreatedBy().equals(99L)));
    }

    @Test
    void createDancer_blankNickname_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.createDancer(1L, new CreateDancerRequest("   ", null, null, null, null, null), false));
        assertEquals(1001, ex.getCode());
        verify(dancerRepository, never()).save(any());
    }

    @Test
    void createDancer_withHomeVenue_attachesRelation() {
        when(dancerRepository.save(any(Dancer.class))).thenAnswer(inv -> {
            Dancer d = inv.getArgument(0);
            d.setId(7L);
            return d;
        });
        when(dancerVenueRepository.findByDancerIdAndVenueIdAndRelationAndDeletedFalse(
                eq(7L), eq(5L), anyString())).thenReturn(Optional.empty());

        dancerService.createDancer(1L, new CreateDancerRequest("小雅", null, null, null, null, 5L), false);

        verify(venueLookupService).findById(5L); // 常驻舞厅存在性校验
        verify(dancerVenueRepository).save(argThat(dv ->
                dv.getDancerId().equals(7L) && dv.getVenueId().equals(5L)));
    }

    // ─── 可见性规则 ─────────────────────────────────────────────────────────

    @Test
    void getDetail_normal_isPublicToEveryone() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{5L, 1L, 3L, 4L});
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());
        when(dancerVenueRepository.findVenueBriefsByDancerIds(anyList())).thenReturn(Collections.emptyList());
        when(recognitionTagRepository.aggregateByDancer(1L)).thenReturn(Collections.emptyList());

        DancerDetailResponse resp = dancerService.getDetail(1L, null, null);

        assertEquals("小雅", resp.nickname());
        assertEquals(5L, resp.stats().countAll());
        assertEquals(DancerStatus.NORMAL, resp.status());
    }

    @Test
    void getDetail_pending_hiddenFromOthers() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.getDetail(1L, 99L, UserRole.USER));
        assertEquals(1003, ex.getCode());
    }

    @Test
    void getDetail_pending_visibleToCreator() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(eq(1L), eq(1L), any()))
                .thenReturn(Optional.empty());
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());
        when(dancerVenueRepository.findVenueBriefsByDancerIds(anyList())).thenReturn(Collections.emptyList());
        when(recognitionTagRepository.aggregateByDancer(1L)).thenReturn(Collections.emptyList());

        DancerDetailResponse resp = dancerService.getDetail(1L, 1L, UserRole.USER);

        assertEquals(DancerStatus.PENDING, resp.status());
        assertTrue(resp.isMine(), "创建人应可见并标记 isMine");
    }

    @Test
    void getDetail_pending_visibleToAdmin() {
        dancer.setStatus(DancerStatus.HIDDEN);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(any(), eq(1L), any()))
                .thenReturn(Optional.empty());
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());
        when(dancerVenueRepository.findVenueBriefsByDancerIds(anyList())).thenReturn(Collections.emptyList());
        when(recognitionTagRepository.aggregateByDancer(1L)).thenReturn(Collections.emptyList());

        DancerDetailResponse resp = dancerService.getDetail(1L, 99L, UserRole.ADMIN);

        assertEquals(DancerStatus.HIDDEN, resp.status());
    }

    // ─── 认可（每日一记 toggle） ─────────────────────────────────────────────

    @Test
    void toggleRecognize_firstTime_insertsRecognitionAndTags() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());
        when(recognitionRepository.save(any(DancerRecognition.class))).thenAnswer(inv -> {
            DancerRecognition r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{1L, 1L, 1L, 1L});
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(List.of("DANCE", "EASY_TALK")), UserRole.USER);

        assertTrue(resp.recognized());
        verify(recognitionRepository).save(argThat(r ->
                r.getUserId().equals(2L) && r.getRecognitionDate().equals(LocalDate.now())));
        verify(recognitionTagRepository).save(argThat(t -> t.getTag().equals("DANCE")));
        verify(recognitionTagRepository).save(argThat(t -> t.getTag().equals("EASY_TALK")));
        verify(aggregateService).invalidate(1L);
    }

    @Test
    void toggleRecognize_secondTime_cancelsAndDeletesTags() {
        DancerRecognition existing = new DancerRecognition();
        existing.setId(100L);
        existing.setUserId(2L);
        existing.setDancerId(1L);
        existing.setRecognitionDate(LocalDate.now());

        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(existing));
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{0L, 0L, 0L, 0L});
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());

        RecognizeResponse resp = dancerService.toggleRecognize(2L, 1L, null, UserRole.USER);

        assertFalse(resp.recognized(), "取消当天认可后 recognized=false");
        verify(recognitionTagRepository).deleteByRecognitionId(100L); // 级联删除当日标签
        verify(recognitionRepository).delete(existing);                // 物理删除当日记录
        verify(aggregateService).invalidate(1L);
    }

    @Test
    void toggleRecognize_invalidTag_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(2L, 1L,
                        new RecognizeDancerRequest(List.of("NOT_IN_DICT")), UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_tooManyTags_throws() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(2L, 1L,
                        new RecognizeDancerRequest(List.of("DANCE", "EASY_TALK", "GOOD_VIBE", "FUNNY")), UserRole.USER));
        assertEquals(1001, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    @Test
    void toggleRecognize_duplicateTags_deduplicated() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));
        when(recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(2L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());
        when(recognitionRepository.save(any(DancerRecognition.class))).thenAnswer(inv -> {
            DancerRecognition r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });
        when(aggregateService.getAggregate(1L)).thenReturn(new long[]{1L, 1L, 1L, 1L});
        when(recognitionRepository.countByDay(eq(1L), any())).thenReturn(Collections.emptyList());

        dancerService.toggleRecognize(2L, 1L,
                new RecognizeDancerRequest(List.of("DANCE", "DANCE", "EASY_TALK")), UserRole.USER);

        verify(recognitionTagRepository, times(1)).save(argThat(t -> t.getTag().equals("DANCE")));
        verify(recognitionTagRepository, times(1)).save(argThat(t -> t.getTag().equals("EASY_TALK")));
    }

    @Test
    void toggleRecognize_hiddenDancerByNonOwner_throws() {
        dancer.setStatus(DancerStatus.HIDDEN);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.toggleRecognize(99L, 1L, null, UserRole.USER));
        assertEquals(1003, ex.getCode());
        verify(recognitionRepository, never()).save(any());
    }

    // ─── 我的认可（同舞伴去重，取最近一条） ───────────────────────────────────

    @Test
    void listMyRecognitions_deduplicatesByDancer_keepsMostRecent() {
        // findMyRecognitions 按 createdAt 倒序：先返回最近一条（dancer 1 的 08-05），再返回历史（dancer 1 的 08-01）
        when(recognitionRepository.findMyRecognitions(2L)).thenReturn(List.of(
                new Object[]{200L, 1L, LocalDate.of(2026, 8, 5)},
                new Object[]{100L, 1L, LocalDate.of(2026, 8, 1)},
                new Object[]{300L, 2L, LocalDate.of(2026, 8, 4)}));
        Dancer d1 = new Dancer();
        d1.setId(1L);
        d1.setNickname("小雅");
        Dancer d2 = new Dancer();
        d2.setId(2L);
        d2.setNickname("阿丽");
        when(dancerRepository.findByIds(anyList())).thenReturn(List.of(d1, d2));
        when(recognitionTagRepository.findTagsByRecognitionIds(anyList())).thenReturn(
                List.of(new Object[]{200L, "DANCE"}, new Object[]{300L, "EASY_TALK"}));
        when(dancerVenueRepository.findVenueBriefsByDancerIds(anyList())).thenReturn(Collections.emptyList());

        var result = dancerService.listMyRecognitions(2L);

        assertEquals(2, result.size(), "同舞伴多日认可只保留最近一条");
        assertEquals(1L, result.get(0).dancerId());
        assertEquals(LocalDate.of(2026, 8, 5), result.get(0).recognizedOn());
        assertEquals(List.of("DANCE"), result.get(0).tagCodes());
        assertEquals(2L, result.get(1).dancerId());
    }

    // ─── 管理端状态切换（审核 → 站内信通知创建人） ────────────────────────────

    @Test
    void updateStatus_approvePending_sendsReviewMessage() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.NORMAL, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.NORMAL));
        // 审核通过 → 站内信（DANCER_REVIEW，收件人 = 创建人，软关联 DANCER 详情页）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_REVIEW),
                eq("舞伴主页审核通过"), contains("已通过审核"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_rejectPending_sendsReviewMessageWithReason() {
        dancer.setStatus(DancerStatus.PENDING);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.REJECTED, "昵称与真实身份不符");

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.REJECTED));
        // 驳回原因随站内信回传创建人（reason 清洗后拼入正文）
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_REVIEW),
                eq("舞伴主页未通过审核"), contains("昵称与真实身份不符"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_hideNormal_sendsStatusMessage() {
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.HIDDEN, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.HIDDEN));
        verify(messageService).create(eq(1L), eq(MessageType.DANCER_STATUS),
                eq("舞伴主页已隐藏"), contains("已被隐藏"), eq("DANCER"), eq(1L));
    }

    @Test
    void updateStatus_sameStatus_idempotentNoMessage() {
        // dancer 初始 NORMAL，目标仍 NORMAL：无变更、无通知
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.NORMAL, null);

        verify(dancerRepository, never()).save(any());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void updateStatus_rejectedToHidden_noMessage() {
        dancer.setStatus(DancerStatus.REJECTED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        dancerService.updateStatus(1L, DancerStatus.HIDDEN, null);

        verify(dancerRepository).save(argThat(d -> d.getStatus() == DancerStatus.HIDDEN));
        // REJECTED → HIDDEN 属管理侧内部流转，不产生用户通知
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void getDetail_rejected_hiddenFromOthers() {
        dancer.setStatus(DancerStatus.REJECTED);
        when(dancerRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(dancer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dancerService.getDetail(1L, 99L, UserRole.USER));
        assertEquals(1003, ex.getCode());
    }

    @Test
    void listAdminDancers_mapsRowsWithCreatorFallback() {
        // 管理端列表行：{id, nickname, avatar, bio, gender, city, status, created_at, u.nickname, u.avatar_url}
        // 时间列按 Hibernate 7 native 查询映射为 java.time.LocalDateTime（禁 java.sql.Timestamp，
        // 见后端 AGENTS.md「native 查询时间列强转约定」——2026-08-08 DancerService 修复后
        // 本测试同步，否则 mock 类型与服务强转不一致抛 ClassCastException）。
        var row = new Object[]{1L, "小雅", null, "舞姿优秀", "FEMALE", "杭州", "PENDING",
                LocalDateTime.of(2026, 8, 8, 10, 0), null, null};
        org.springframework.data.domain.Page<Object[]> page =
                new org.springframework.data.domain.PageImpl<Object[]>(Collections.singletonList(row));
        when(dancerRepository.findAdminPage(eq("PENDING"), any())).thenReturn(page);

        var result = dancerService.listAdminDancers(DancerStatus.PENDING, 0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals("小雅", result.getContent().get(0).nickname());
        assertEquals("未知用户", result.getContent().get(0).creatorNickname(), "注册人已删时回退占位");
        assertEquals(DancerStatus.PENDING, result.getContent().get(0).status());
    }
}
