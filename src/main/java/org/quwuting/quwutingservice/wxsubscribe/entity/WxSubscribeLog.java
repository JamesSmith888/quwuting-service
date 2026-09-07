package org.quwuting.quwutingservice.wxsubscribe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 微信订阅消息发送留痕（2026-09-07 新增，V11）。
 * <p>
 * 每用户每次发送一行（成功/失败均留痕），运营复盘「授权 → 触达」漏斗与
 * 模板字段核对（data key 不符 → errcode 47003，看日志定位）。只写不改。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_wx_subscribe_logs", indexes = {
        @Index(name = "qwt_idx_wx_subscribe_logs_user", columnList = "userId"),
        @Index(name = "qwt_idx_wx_subscribe_logs_venue", columnList = "venueId")
})
public class WxSubscribeLog extends BaseEntity {

    /** 接收用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 触发门店 ID（状态变更来源） */
    @Column(nullable = false)
    private Long venueId;

    /** 订阅消息模板 ID */
    @Column(nullable = false, length = 64)
    private String templateId;

    /** 发送是否成功（微信 errcode == 0） */
    @Column(nullable = false)
    private boolean success;

    /** 微信返回错误码（成功为 0；HTTP/解析层失败为 null） */
    @Column
    private Integer errcode;
}
