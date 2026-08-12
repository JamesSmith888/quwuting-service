package org.quwuting.quwutingservice.venuestatuswatcher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuestatuswatcher.entity.VenueStatusWatcher;
import org.quwuting.quwutingservice.venuestatuswatcher.repository.VenueStatusWatcherRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VenueStatusWatcherService 单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-12「关注门店营业状态通知」契约：
 * <ol>
 *   <li>watch 幂等（重复开启不重复插入）+ 门店不存在抛 1001；</li>
 *   <li>unwatch / isWatching（物理删除语义）；</li>
 *   <li>notifyStatusChanged 向全部关注者发 VENUE_STATUS_CHANGED 站内信
 *       （同事务调用 MessageService#create，软关联 VENUE）；</li>
 *   <li>无关注者 / 门店软删时不发消息（空通知短路）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VenueStatusWatcherServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long VENUE_ID = 13L;

    @Mock
    private VenueStatusWatcherRepository watcherRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private MessageService messageService;

    private VenueStatusWatcherService newService() {
        return new VenueStatusWatcherService(watcherRepository, venueRepository, messageService);
    }

    private Venue mockVenue(String name) {
        Venue venue = new Venue();
        venue.setId(VENUE_ID);
        venue.setName(name);
        venue.setStatus(VenueStatus.OPEN);
        return venue;
    }

    // ── watch / unwatch / isWatching ────────────────────────────────────────────

    @Test
    void watch_幂等_重复开启只插入一次() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID))
                .thenReturn(Optional.of(mockVenue("XX舞厅")));
        // 调用序列：首次 exists=false（走插入），再次 exists=true（幂等早退）——
        // mock 按序返回，验证"重复开启不重复插入"的幂等分支
        when(watcherRepository.existsByUserIdAndVenueIdAndDeletedFalse(USER_ID, VENUE_ID))
                .thenReturn(false, true);

        VenueStatusWatcherService service = newService();
        service.watch(USER_ID, VENUE_ID);
        service.watch(USER_ID, VENUE_ID); // 第二次：exists=true 分支幂等

        verify(watcherRepository).save(any(VenueStatusWatcher.class));
    }

    @Test
    void watch_门店不存在_抛1001() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.empty());

        VenueStatusWatcherService service = newService();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.watch(USER_ID, VENUE_ID));
        assertEquals(1001, ex.getCode());
        verify(watcherRepository, never()).save(any());
    }

    @Test
    void unwatch_物理删除_幂等() {
        VenueStatusWatcherService service = newService();
        service.unwatch(USER_ID, VENUE_ID);
        verify(watcherRepository).deleteByUserIdAndVenueId(USER_ID, VENUE_ID);
    }

    @Test
    void isWatching_透传仓储判定() {
        when(watcherRepository.existsByUserIdAndVenueIdAndDeletedFalse(USER_ID, VENUE_ID))
                .thenReturn(true);
        assertEquals(true, newService().isWatching(USER_ID, VENUE_ID));
    }

    // ── notifyStatusChanged ─────────────────────────────────────────────────────

    @Test
    void notifyStatusChanged_向全部关注者发站内信_软关联VENUE() {
        Venue venue = mockVenue("XX舞厅");
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.of(venue));
        VenueStatusWatcher w1 = new VenueStatusWatcher();
        w1.setUserId(USER_ID);
        VenueStatusWatcher w2 = new VenueStatusWatcher();
        w2.setUserId(8L);
        when(watcherRepository.findByVenueIdAndDeletedFalse(VENUE_ID))
                .thenReturn(List.of(w1, w2));

        newService().notifyStatusChanged(VENUE_ID, VenueStatus.OPEN, VenueStatus.SUSPENDED);

        verify(messageService).create(USER_ID, MessageType.VENUE_STATUS_CHANGED,
                "门店状态更新", "「XX舞厅」暂停营业（原营业中）", "VENUE", VENUE_ID);
        verify(messageService).create(8L, MessageType.VENUE_STATUS_CHANGED,
                "门店状态更新", "「XX舞厅」暂停营业（原营业中）", "VENUE", VENUE_ID);
    }

    @Test
    void notifyStatusChanged_恢复营业_文案为已恢复营业() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID))
                .thenReturn(Optional.of(mockVenue("XX舞厅")));
        VenueStatusWatcher w = new VenueStatusWatcher();
        w.setUserId(USER_ID);
        when(watcherRepository.findByVenueIdAndDeletedFalse(VENUE_ID)).thenReturn(List.of(w));

        newService().notifyStatusChanged(VENUE_ID, VenueStatus.SUSPENDED, VenueStatus.OPEN);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).create(anyLong(), any(MessageType.class), any(),
                contentCaptor.capture(), any(), anyLong());
        assertEquals("「XX舞厅」已恢复营业（原暂停营业）", contentCaptor.getValue());
    }

    @Test
    void notifyStatusChanged_无关注者_不发消息() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID))
                .thenReturn(Optional.of(mockVenue("XX舞厅")));
        when(watcherRepository.findByVenueIdAndDeletedFalse(VENUE_ID)).thenReturn(List.of());

        newService().notifyStatusChanged(VENUE_ID, VenueStatus.OPEN, VenueStatus.SUSPENDED);

        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void notifyStatusChanged_门店已软删_不发消息() {
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID)).thenReturn(Optional.empty());

        newService().notifyStatusChanged(VENUE_ID, VenueStatus.OPEN, VenueStatus.SUSPENDED);

        verify(watcherRepository, never()).findByVenueIdAndDeletedFalse(anyLong());
        verify(messageService, never()).create(anyLong(), any(), any(), any(), any(), anyLong());
    }
}
