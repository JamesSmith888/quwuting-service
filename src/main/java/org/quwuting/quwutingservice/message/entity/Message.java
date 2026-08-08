package org.quwuting.quwutingservice.message.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.message.enums.MessageType;

import java.time.LocalDateTime;

/**
 * 站内信（消息中心通用消息实体）。
 * <p>
 * 通用性设计（2026-08-08 新增，见 AGENTS.md「站内信（消息中心）」）：
 * 一条消息 = 收件人 + 类型 + 标题 + 内容 + 业务关联（relatedType/relatedId 软关联，
 * 前端按关联类型深链对应详情页——当前 DANCER → 舞伴详情页，后续可扩展 VENUE 等）；
 * readAt 为可空列，null = 未读（未读数徽标查询依据）。
 * <p>
 * 与「我的上报」的边界：上报记录（qwt_venue_feedbacks / qwt_venue_status_reports）是
 * 用户主动动作的业务数据，管理员处理结果经原记录回传（handleNote），不复制为站内信；
 * 站内信承载平台对用户的<b>主动通知</b>（审核结果等），二者在消息中心页面统一展示
 * 但数据源独立（见前端「消息中心」章节）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_messages", indexes = {
        @Index(name = "qwt_idx_messages_user_created", columnList = "userId, createdAt"),
        @Index(name = "qwt_idx_messages_user_read", columnList = "userId, readAt")
})
public class Message extends BaseEntity {

    /** 收件人用户 ID（消息是用户级资源，按 userId 查询） */
    @Column(nullable = false)
    private Long userId;

    /** 消息类型（消息中心分类，前端按 code 渲染文案/图标） */
    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MessageType type;

    /** 标题（列表行主展示文案，如"舞伴主页审核结果"） */
    @Column(length = 100, nullable = false)
    private String title;

    /** 内容（正文，如"你的舞伴主页「小雅」已通过审核……"；驳回时附原因） */
    @Column(length = 500, nullable = false)
    private String content;

    /** 业务关联类型（如 DANCER；null = 无关联目标，前端不深链） */
    @Column(length = 30)
    private String relatedType;

    /** 业务关联 ID（如舞伴 ID；与 relatedType 成对出现） */
    private Long relatedId;

    /** 已读时间（null = 未读；用户打开消息中心后批量置当前时间） */
    private LocalDateTime readAt;
}
