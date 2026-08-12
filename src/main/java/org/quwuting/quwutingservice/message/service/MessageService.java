package org.quwuting.quwutingservice.message.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.message.dto.response.MessageResponse;
import org.quwuting.quwutingservice.message.entity.Message;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.repository.MessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信服务（通用消息中心后端，2026-08-08 新增，见 AGENTS.md「站内信（消息中心）」）。
 * <ul>
 *   <li><b>写</b>：{@link #create}——业务模块（当前为舞伴审核、上报处理结果）在状态
 *       流转时调用，发件人是平台（无发件人概念），收件人 = 业务关联用户；</li>
 *   <li><b>读</b>：{@link #list} 分页倒序 + {@link #unreadCount}（未读徽标）；
 *       {@link #markOneRead} / {@link #markAllRead} 标记已读（打开消息中心即全量已读）。</li>
 * </ul>
 * 文本防注入：title/content 入库前统一经 {@link TextSanitizer} 清洗（控制字符剥离 + 截断），
 * 与 venuefeedback 模块同约定；XSS 由小程序 {@code <text>} 文本节点渲染天然转义。
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    /** 标题最长字符数（与 qwt_messages.title varchar(100) 一致） */
    private static final int TITLE_MAX = 100;
    /** 内容最长字符数（与 qwt_messages.content varchar(500) 一致） */
    private static final int CONTENT_MAX = 500;

    private final MessageRepository messageRepository;

    /**
     * 创建站内信（业务模块调用；无需当前登录态——审核方与管理方可能不同）。
     *
     * @param userId 收件人用户 ID
     * @param type 消息类型（消息中心分类）
     * @param title 标题
     * @param content 正文（驳回原因等动态内容在此拼接后传入）
     * @param relatedType 业务关联类型（如 DANCER），可为 null
     * @param relatedId 业务关联 ID，可为 null
     */
    @Transactional
    public void create(Long userId, MessageType type, String title, String content,
                       String relatedType, Long relatedId) {
        Message message = new Message();
        message.setUserId(userId);
        message.setType(type);
        message.setTitle(TextSanitizer.sanitize(title, TITLE_MAX));
        message.setContent(TextSanitizer.sanitize(content, CONTENT_MAX));
        message.setRelatedType(relatedType);
        message.setRelatedId(relatedId);
        messageRepository.save(message);
    }

    /** 我的站内信（按创建时间倒序分页） */
    @Transactional(readOnly = true)
    public Page<MessageResponse> list(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Message> rows = messageRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, pageable);
        List<MessageResponse> content = rows.getContent().stream()
                .map(m -> new MessageResponse(
                        m.getId(), m.getType(), m.getTitle(), m.getContent(),
                        m.getRelatedType(), m.getRelatedId(), m.getReadAt() != null, m.getCreatedAt()))
                .toList();
        return new PageImpl<>(content, pageable, rows.getTotalElements());
    }

    /** 未读消息数（个人中心 / 首页 FAB 未读徽标依据） */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return messageRepository.countByUserIdAndReadAtIsNullAndDeletedFalse(userId);
    }

    /**
     * 未读的关注门店状态变化提醒（首页提醒卡片数据源，2026-08-12 新增）：
     * 取类型为 {@link MessageType#VENUE_STATUS_CHANGED} 的最新未读消息前 N 条
     * （不返回分页元数据——卡片是轻量聚合，见 MessageController#statusAlerts）。
     * limit 收敛到 [1, 10]，默认由 Controller 决定。
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> listStatusAlerts(Long userId, int limit) {
        int size = Math.min(Math.max(limit, 1), 10);
        return messageRepository
                .findByUserIdAndTypeAndReadAtIsNullAndDeletedFalseOrderByCreatedAtDesc(
                        userId, MessageType.VENUE_STATUS_CHANGED, PageRequest.of(0, size))
                .getContent().stream()
                .map(m -> new MessageResponse(
                        m.getId(), m.getType(), m.getTitle(), m.getContent(),
                        m.getRelatedType(), m.getRelatedId(), m.getReadAt() != null, m.getCreatedAt()))
                .toList();
    }

    /** 单条标记已读（越权/重复已读幂等——影响行数为 0 时静默成功） */
    @Transactional
    public void markOneRead(Long userId, Long messageId) {
        messageRepository.markOneRead(messageId, userId, LocalDateTime.now());
    }

    /** 全部标记已读（用户打开消息中心后批量置为已读；幂等） */
    @Transactional
    public void markAllRead(Long userId) {
        messageRepository.markAllRead(userId, LocalDateTime.now());
    }
}
