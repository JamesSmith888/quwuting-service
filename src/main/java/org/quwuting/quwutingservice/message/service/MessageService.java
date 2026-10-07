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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 创建或合并未读站内信（2026-10-07，通知折叠原语）。
     * <p>
     * 同一收件人已有<b>同类型、同业务关联、仍未读、且创建于 {@code mergeSince} 之后</b>的消息时，
     * 就地更新那一条的标题与正文（创建时间与未读态不变 ⇒ 未读徽标数不增加），否则新建。
     * 用于「一件事被多次触发」的通知（热门上报被 10 人点赞 = 10 条消息会淹没消息中心）：
     * 调用方把<b>累计结果</b>写进正文（如「收到 N 个赞」），本方法只负责「别再多出一行」。
     * <p>
     * 已读之后再触发 ⇒ 新建（用户已经看过上一条，这是新的信息）。并发两次触发可能各自新建一条
     * （无锁、无唯一键）——接受：窗口极小、后果只是多一行提示。
     */
    @Transactional
    public void createOrMergeUnread(Long userId, MessageType type, String title, String content,
                                    String relatedType, Long relatedId, LocalDateTime mergeSince) {
        Message existing = messageRepository
                .findFirstByUserIdAndTypeAndRelatedTypeAndRelatedIdAndReadAtIsNullAndDeletedFalseAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        userId, type, relatedType, relatedId, mergeSince)
                .orElse(null);
        if (existing == null) {
            create(userId, type, title, content, relatedType, relatedId);
            return;
        }
        existing.setTitle(TextSanitizer.sanitize(title, TITLE_MAX));
        existing.setContent(TextSanitizer.sanitize(content, CONTENT_MAX));
        messageRepository.save(existing);
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

    /**
     * 未读的关注门店状态变化提醒对应的门店 ID 集合（收藏列表「状态更新」角标数据源，
     * 2026-09-01「收藏即关注」）：批量一次 IN 查询避免 N+1（同收藏列表整页批量模式）。
     * venueIds 为空时返回空集合（跳过查询，零往返）。
     */
    @Transactional(readOnly = true)
    public Set<Long> findUnreadStatusChangedVenueIds(Long userId, Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(messageRepository.findUnreadVenueIdsByType(
                userId, MessageType.VENUE_STATUS_CHANGED, venueIds));
    }

    /**
     * 按门店标记关注状态变化提醒已读（收藏门店状态角标消费，2026-09-01）：
     * 打开门店详情 = 已看到最新状态 → 该店全部未读 VENUE_STATUS_CHANGED 置已读。
     * 幂等：无未读时影响行数 0（收藏列表返回 onShow 重拉自然收敛角标）；
     * 仅处理该类型，不影响其他类型站内信。
     */
    @Transactional
    public void markStatusChangedReadByVenue(Long userId, Long venueId) {
        messageRepository.markReadByVenueAndType(
                userId, MessageType.VENUE_STATUS_CHANGED, venueId, LocalDateTime.now());
    }
}
