package org.quwuting.quwutingservice.webauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * Web 管理后台扫码登录会话（qwt_web_login_sessions，2026-08-31）。
 * <p>
 * 扫码登录链路：网页生成会话 → 用户微信扫小程序码打开「确认登录」页 →
 * 小程序内确认（后端校验 ADMIN）→ 网页轮询取 token。
 * <ul>
 *   <li>sessionId：29 位随机 hex（防枚举），加 1 字符前缀组成小程序码 scene（共 30 字符 ≤32 上限）</li>
 *   <li>status：PENDING / CONFIRMED / REJECTED / EXPIRED</li>
 *   <li>tokenIssued：确认时签发的 JWT，轮询取走后置空（一次性下发，防重放）</li>
 *   <li>expiresAt：5 分钟 TTL，轮询时惰性置 EXPIRED</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_web_login_sessions", indexes = {
        @Index(name = "qwt_idx_web_login_sessions_sid", columnList = "sessionId", unique = true),
        @Index(name = "qwt_idx_web_login_sessions_status", columnList = "status")
})
public class WebLoginSession extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    /** 确认登录的管理员用户 ID（PENDING 时为 null） */
    @Column
    private Long userId;

    /** 确认时签发的 JWT（一次性下发：轮询取走后置空） */
    @Column(length = 1024)
    private String tokenIssued;

    @Column(nullable = false)
    private LocalDateTime expiresAt;
}
