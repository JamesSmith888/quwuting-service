package org.quwuting.quwutingservice.message.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.message.entity.Message;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.repository.MessageRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通知折叠原语：未读合并（就地更新正文）/ 已读或无同类则新建。 */
@ExtendWith(MockitoExtension.class)
class MessageServiceMergeTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 7, 20, 0);

    @Mock
    private MessageRepository messageRepository;

    private MessageService service;

    @BeforeEach
    void setUp() {
        service = new MessageService(messageRepository);
    }

    @Test
    void mergesIntoTheUnreadMessageInsteadOfAddingARow() {
        Message existing = new Message();
        existing.setUserId(10L);
        existing.setType(MessageType.CROWD_REPORT_LIKED);
        existing.setTitle("收到热度点赞");
        existing.setContent("收到 1 个赞");
        when(messageRepository
                .findFirstByUserIdAndTypeAndRelatedTypeAndRelatedIdAndReadAtIsNullAndDeletedFalseAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        10L, MessageType.CROWD_REPORT_LIKED, "VENUE", 100L, SINCE))
                .thenReturn(Optional.of(existing));

        service.createOrMergeUnread(10L, MessageType.CROWD_REPORT_LIKED, "收到热度点赞", "收到 3 个赞",
                "VENUE", 100L, SINCE);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(1)).save(saved.capture());
        assertEquals(existing, saved.getValue(), "保存的就是原来那一行（没有新增）");
        assertEquals("收到 3 个赞", saved.getValue().getContent());
        assertNull(saved.getValue().getReadAt(), "仍是未读 ⇒ 未读徽标数不增加");
    }

    @Test
    void createsANewMessageWhenThereIsNothingUnreadToMergeInto() {
        when(messageRepository
                .findFirstByUserIdAndTypeAndRelatedTypeAndRelatedIdAndReadAtIsNullAndDeletedFalseAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        eq(10L), any(), eq("VENUE"), eq(100L), eq(SINCE)))
                .thenReturn(Optional.empty());

        service.createOrMergeUnread(10L, MessageType.CROWD_REPORT_LIKED, "收到热度点赞", "收到 1 个赞",
                "VENUE", 100L, SINCE);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(1)).save(saved.capture());
        assertEquals(10L, saved.getValue().getUserId());
        assertEquals("收到 1 个赞", saved.getValue().getContent());
        assertEquals("VENUE", saved.getValue().getRelatedType());
        assertEquals(100L, saved.getValue().getRelatedId());
    }
}
