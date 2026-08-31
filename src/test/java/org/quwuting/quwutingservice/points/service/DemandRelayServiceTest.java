package org.quwuting.quwutingservice.points.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerServiceRepository;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.dancer.service.DancerUnlockCacheInvalidator;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.dto.AdminDemandItem;
import org.quwuting.quwutingservice.points.repository.PointsUnlockRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DemandRelayService 管理端邀约工作台单元测试（Mockito，不依赖数据库）。
 * <p>
 * 2026-08-28 根因回归（docs/agents/25「反馈闭环 · 管理端可见性修复」）：
 * 客人反馈（guest_feedback）只发生在<b>非中转舞伴（contact_relay=false）</b>的
 * 已发放/存量邀约上，而工作台原查询范围 = 中转舞伴集合 + PENDING 状态——两个
 * 数据集合不相交，反馈落库后管理端零可见（生产实证：feedback_requested_at 非空
 * 的邀约 100% 非中转）。本测试锁定修复契约：
 * <ul>
 *   <li>{@code countPending} = 中转 PENDING + 全舞伴反馈未核实（me 页红点包含反馈）；</li>
 *   <li>{@code listByScope(pending)} 并入非中转舞伴的反馈待办（反馈时间倒序浮顶）；</li>
 *   <li>{@code markFeedbackHandled} 幂等归档（重复核实静默、无反馈 1001）；</li>
 *   <li>processed/all 视图保持中转舞伴口径不变（反馈已核实行归入已处理视图）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DemandRelayServiceTest {

    @Mock
    private DemandRecordRepository demandRecordRepository;
    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private DancerServiceRepository dancerServiceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PointsUnlockRepository unlockRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private DancerUnlockCacheInvalidator dancerUnlockCacheInvalidator;
    @Mock
    private ContributionService contributionService;

    private DemandRelayService service;

    @BeforeEach
    void setUp() {
        service = new DemandRelayService(demandRecordRepository, dancerRepository,
                dancerServiceRepository, userRepository, unlockRepository,
                messageService, dancerUnlockCacheInvalidator, contributionService);
    }

    // ─── 待办红点：反馈计入 pending-count（2026-08-28 根因修复核心） ──────────

    @Test
    void countPending_includesFeedbackPending() {
        Dancer relay = dancer(1L, true);
        when(dancerRepository.findRelayEnabled()).thenReturn(List.of(relay));
        when(demandRecordRepository.countPendingByDancerIds(any())).thenReturn(3L);
        when(demandRecordRepository.countPendingFeedback()).thenReturn(2L);

        long count = service.countPending();

        assertEquals(5L, count);
        verify(demandRecordRepository).countPendingByDancerIds(any());
        verify(demandRecordRepository).countPendingFeedback();
    }

    @Test
    void countPending_zeroWhenNoRelayAndNoFeedback() {
        when(dancerRepository.findRelayEnabled()).thenReturn(List.of());
        when(demandRecordRepository.countPendingFeedback()).thenReturn(0L);

        assertEquals(0L, service.countPending());
        verify(demandRecordRepository, never()).countPendingByDancerIds(any());
    }

    // ─── 待处理视图：非中转舞伴的反馈待办并入列表（根因修复核心） ────────────

    @Test
    void listPending_mergesFeedbackFromNonRelayDancer() {
        // 中转舞伴 1：一条 PENDING 待发放（relay 集合内）
        Dancer relay = dancer(1L, true);
        DemandRecord pendingRelay = record(11L, 1L, 2L, "PENDING", null, null);
        // 非中转舞伴 13：一条已反馈邀约（feedbackRequestedAt 最新）——原查询范围不可见
        DemandRecord feedbackRecord = record(77L, 13L, 3L, null, "ADD_FAILED",
                LocalDateTime.of(2026, 8, 28, 11, 59, 44));

        when(dancerRepository.findRelayEnabled()).thenReturn(List.of(relay));
        when(demandRecordRepository.findPendingByDancerIds(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRelay), PageRequest.of(0, 20), 1));
        when(demandRecordRepository.findPendingFeedback(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(feedbackRecord), PageRequest.of(0, 500), 1));
        when(dancerRepository.findByIds(any())).thenReturn(List.of(relay, dancer(13L, false)));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(2L), user(3L)));
        when(contributionService.aggregatesFor(anyList())).thenReturn(Map.of());
        when(demandRecordRepository.countConfirmedGroupByUserIdsAndDancerIds(any(), any()))
                .thenReturn(List.of());

        Page<AdminDemandItem> page = service.listByScope("pending", 0, 20);

        assertEquals(2, page.getTotalElements());
        // 反馈待办在前（feedbackRequestedAt 最新 → 浮顶优先处理）
        assertEquals(77L, page.getContent().get(0).id());
        assertTrue(page.getContent().get(0).guestFeedback() != null);
        assertFalse(page.getContent().get(0).guestFeedbackHandled());
        // PENDING 待发放在后（createdAt 排序键）
        assertEquals(11L, page.getContent().get(1).id());
        assertEquals("PENDING", page.getContent().get(1).status());
    }

    @Test
    void listPending_noRelay_stillShowsFeedback() {
        // 极端：无任何中转舞伴——反馈待办仍须可见（这是"admin 收不到消息"的最坏形态）
        DemandRecord feedbackRecord = record(77L, 13L, 3L, null, "REJECTED",
                LocalDateTime.of(2026, 8, 28, 11, 59, 44));

        when(dancerRepository.findRelayEnabled()).thenReturn(List.of());
        when(demandRecordRepository.findPendingFeedback(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(feedbackRecord), PageRequest.of(0, 500), 1));
        when(dancerRepository.findByIds(any())).thenReturn(List.of(dancer(13L, false)));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(3L)));
        when(contributionService.aggregatesFor(anyList())).thenReturn(Map.of());
        when(demandRecordRepository.countConfirmedGroupByUserIdsAndDancerIds(any(), any()))
                .thenReturn(List.of());

        Page<AdminDemandItem> page = service.listByScope("pending", 0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals(77L, page.getContent().get(0).id());
        assertEquals("REJECTED", page.getContent().get(0).guestFeedback());
    }

    // ─── 已处理视图口径不变：仍限中转舞伴，反馈已核实行归入 ───────────────────

    @Test
    void listProcessed_keepsRelayScope() {
        Dancer relay = dancer(1L, true);
        when(dancerRepository.findRelayEnabled()).thenReturn(List.of(relay));
        when(demandRecordRepository.findByDancerIdsAndStatuses(any(), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        Page<AdminDemandItem> page = service.listByScope("processed", 0, 20);

        assertEquals(0, page.getTotalElements());
        verify(demandRecordRepository).findByDancerIdsAndStatuses(any(), anyList(), any(Pageable.class));
        verify(demandRecordRepository, never()).findPendingFeedback(any(Pageable.class));
    }

    // ─── 反馈已核实闭环（V58 新增管理端操作） ─────────────────────────────────

    @Test
    void markFeedbackHandled_setsHandledAt() {
        DemandRecord record = record(77L, 13L, 3L, null, "ADD_FAILED",
                LocalDateTime.of(2026, 8, 28, 11, 59, 44));
        when(demandRecordRepository.findById(77L)).thenReturn(Optional.of(record));
        when(demandRecordRepository.updateFeedbackHandledIf(any(Long.class), any(LocalDateTime.class)))
                .thenReturn(1);

        service.markFeedbackHandled(77L);

        verify(demandRecordRepository).updateFeedbackHandledIf(
                org.mockito.ArgumentMatchers.eq(77L), any(LocalDateTime.class));
    }

    @Test
    void markFeedbackHandled_noFeedback_throws() {
        DemandRecord record = record(11L, 1L, 2L, "PENDING", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markFeedbackHandled(11L));
        assertEquals("该邀约无客人反馈", ex.getMessage());
        verify(demandRecordRepository, never()).updateFeedbackHandledIf(any(Long.class), any(LocalDateTime.class));
    }

    @Test
    void markFeedbackHandled_idempotent_whenAlreadyHandled() {
        DemandRecord record = record(77L, 13L, 3L, null, "ADD_FAILED",
                LocalDateTime.of(2026, 8, 28, 11, 59, 44));
        record.setGuestFeedbackHandledAt(LocalDateTime.of(2026, 8, 28, 12, 5, 0));
        when(demandRecordRepository.findById(77L)).thenReturn(Optional.of(record));
        // 已核实过：条件更新返回 0 = 幂等成功静默（不抛错）
        when(demandRecordRepository.updateFeedbackHandledIf(any(Long.class), any(LocalDateTime.class)))
                .thenReturn(0);

        service.markFeedbackHandled(77L); // 无异常即通过
        verify(demandRecordRepository).updateFeedbackHandledIf(
                org.mockito.ArgumentMatchers.eq(77L), any(LocalDateTime.class));
    }

    // ─── 获批 = 解锁事件（2026-08-31 契约锁定：解锁统计/列表缓存的输入护栏） ───
    //
    // 背景（2026-08-31「邀约解锁统计图未记录」排查）：解锁记录的写路径共有四条
    // （直连解锁 / 中转获批 / 自动发放 / 代找替代），获批即写 PointsUnlock 是
    // 「舞伴统计 unlockStats + 排名热度 + 列表 HOT 排序」全部输入的源头契约。
    // 本组测试锁定：任一真实写入路径都必须 ① insertIfAbsent ② afterUnlockWrite
    // 失效舞伴域缓存矩阵；幂等跳过（记录已存在）不失效；拒绝路径永不写解锁。
    // 此前该契约零测试覆盖，缓存失效矩阵曾发生静默漂移（中转路径漏失效列表缓存）。

    @Test
    void approve_writesUnlockAndInvalidatesDancerCaches() {
        DemandRecord pending = record(11L, 601L, 901L, "PENDING", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(dancerRepository.findByIdAndDeletedFalse(601L)).thenReturn(Optional.of(dancer(601L, true)));
        when(demandRecordRepository.updateStatusIfPending(11L, "APPROVED")).thenReturn(1);
        when(unlockRepository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq("DANCER_CONTACT"),
                org.mockito.ArgumentMatchers.eq(601L), any(LocalDateTime.class)))
                .thenReturn(1);

        service.approve(11L);

        verify(unlockRepository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq("DANCER_CONTACT"),
                org.mockito.ArgumentMatchers.eq(601L), any(LocalDateTime.class));
        // 失效矩阵 = 协调器单入口（详情族级联 + 列表精失效），缺一即统计/排序陈旧
        verify(dancerUnlockCacheInvalidator).afterUnlockWrite(601L);
    }

    @Test
    void approve_idempotentSkip_doesNotInvalidate() {
        DemandRecord pending = record(11L, 601L, 901L, "PENDING", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(dancerRepository.findByIdAndDeletedFalse(601L)).thenReturn(Optional.of(dancer(601L, true)));
        when(demandRecordRepository.updateStatusIfPending(11L, "APPROVED")).thenReturn(1);
        // insertIfAbsent 返回 0 = 解锁记录已存在（同 user×dancer 历史已解锁）
        when(unlockRepository.insertIfAbsent(any(), any(), any(), any(LocalDateTime.class)))
                .thenReturn(0);

        service.approve(11L);

        // 无新数据 → 不失效（对齐 PointsService 幂等分支同款边界）
        verify(dancerUnlockCacheInvalidator, never()).afterUnlockWrite(any());
    }

    @Test
    void approve_alreadyProcessed_throwsWithoutUnlockWrite() {
        DemandRecord pending = record(11L, 601L, 901L, "APPROVED", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(pending));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.approve(11L));
        assertEquals("该邀约已处理", ex.getMessage());
        verify(demandRecordRepository, never()).updateStatusIfPending(any(), any());
        verify(unlockRepository, never()).insertIfAbsent(any(), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void approve_dancerOffShelf_throwsWithoutUnlockWrite() {
        DemandRecord pending = record(11L, 601L, 901L, "PENDING", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(dancerRepository.findByIdAndDeletedFalse(601L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.approve(11L));
        assertEquals("该舞伴已下架，无法发放", ex.getMessage());
        verify(demandRecordRepository, never()).updateStatusIfPending(any(), any());
        verify(unlockRepository, never()).insertIfAbsent(any(), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void reject_neverWritesUnlock() {
        DemandRecord pending = record(11L, 601L, 901L, "PENDING", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(demandRecordRepository.updateRejectIfPending(org.mockito.ArgumentMatchers.eq(11L), any()))
                .thenReturn(1);

        service.reject(11L, null);

        verify(demandRecordRepository).updateRejectIfPending(org.mockito.ArgumentMatchers.eq(11L), any());
        verify(unlockRepository, never()).insertIfAbsent(any(), any(), any(), any(LocalDateTime.class));
        verify(dancerUnlockCacheInvalidator, never()).afterUnlockWrite(any());
    }

    @Test
    void autoRelease_release_writesUnlockAndInvalidates() {
        DemandRecord overdue = record(11L, 601L, 901L, "PENDING", null, null);
        Dancer dancer = dancer(601L, true);
        dancer.setAutoRelease(true);
        when(demandRecordRepository.findPendingOlderThan(any(LocalDateTime.class)))
                .thenReturn(List.of(overdue));
        when(dancerRepository.findByIdAndDeletedFalse(601L)).thenReturn(Optional.of(dancer));
        when(demandRecordRepository.updateStatusIfPending(11L, "AUTO_RELEASED")).thenReturn(1);
        when(unlockRepository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq("DANCER_CONTACT"),
                org.mockito.ArgumentMatchers.eq(601L), any(LocalDateTime.class)))
                .thenReturn(1);

        int handled = service.autoRelease();

        assertEquals(1, handled);
        verify(unlockRepository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L), any(),
                org.mockito.ArgumentMatchers.eq(601L), any(LocalDateTime.class));
        verify(dancerUnlockCacheInvalidator).afterUnlockWrite(601L);
    }

    @Test
    void autoRelease_expired_noUnlockWrite() {
        DemandRecord overdue = record(11L, 601L, 901L, "PENDING", null, null);
        Dancer dancer = dancer(601L, true);
        dancer.setAutoRelease(false); // 告知未回复，不发放
        when(demandRecordRepository.findPendingOlderThan(any(LocalDateTime.class)))
                .thenReturn(List.of(overdue));
        when(dancerRepository.findByIdAndDeletedFalse(601L)).thenReturn(Optional.of(dancer));
        when(demandRecordRepository.updateStatusIfPending(11L, "EXPIRED")).thenReturn(1);

        int handled = service.autoRelease();

        assertEquals(1, handled);
        verify(unlockRepository, never()).insertIfAbsent(any(), any(), any(), any(LocalDateTime.class));
        verify(dancerUnlockCacheInvalidator, never()).afterUnlockWrite(any());
    }

    @Test
    void rescue_writesUnlockForTargetDancer() {
        DemandRecord rejected = record(11L, 601L, 901L, "REJECTED", null, null);
        when(demandRecordRepository.findById(11L)).thenReturn(Optional.of(rejected));
        when(dancerRepository.findByIdAndDeletedFalse(602L)).thenReturn(Optional.of(dancer(602L, true)));
        when(demandRecordRepository.existsByOriginDemandId(11L)).thenReturn(false);
        when(demandRecordRepository.save(any(DemandRecord.class))).thenAnswer(inv -> {
            DemandRecord r = inv.getArgument(0);
            r.setId(99L);
            return r;
        });
        when(unlockRepository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq("DANCER_CONTACT"),
                org.mockito.ArgumentMatchers.eq(602L), any(LocalDateTime.class)))
                .thenReturn(1);

        Long newDemandId = service.rescue(11L, 602L);

        assertEquals(99L, newDemandId);
        // 解锁记录 = 替代舞伴（而非原舞伴）——统计归属与发放对象一致
        verify(unlockRepository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq("DANCER_CONTACT"),
                org.mockito.ArgumentMatchers.eq(602L), any(LocalDateTime.class));
        verify(dancerUnlockCacheInvalidator).afterUnlockWrite(602L);
    }

    // ─── 工具 ────────────────────────────────────────────────────────────────

    private static Dancer dancer(Long id, boolean relay) {
        Dancer d = new Dancer();
        d.setId(id);
        d.setNickname("舞伴" + id);
        d.setCity("上海");
        d.setContactRelay(relay);
        return d;
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setNickname("客人" + id);
        u.setAvatarUrl("http://avatar/" + id);
        u.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        u.setDeleted(false);
        return u;
    }

    private static DemandRecord record(Long id, Long dancerId, Long userId, String status,
                                       String guestFeedback, LocalDateTime feedbackAt) {
        DemandRecord r = new DemandRecord();
        r.setId(id);
        r.setDancerId(dancerId);
        r.setUserId(userId);
        r.setStatus(status);
        r.setGuestFeedback(guestFeedback);
        r.setFeedbackRequestedAt(feedbackAt);
        r.setCreatedAt(LocalDateTime.of(2026, 8, 28, 9, 0).plusMinutes(id));
        r.setMessage("「去舞厅」：按时段 · KTV · 近3天内 · 2小时 · 同城");
        return r;
    }
}
