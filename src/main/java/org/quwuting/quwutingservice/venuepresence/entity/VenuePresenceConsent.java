package org.quwuting.quwutingservice.venuepresence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;

/**
 * 到店足迹开关状态流水（2026-09-29 四轮，V34；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * 一行 = 一次「状态确立」：DEFAULT = 默认态确立（第一次采集 ping 时该用户无任何
 * consent 行则补一条 enabled=true，默认开启人群由此进入统计）；USER = 用户在
 * 「我的-设置」手动拨动开关。当前态 = 每用户最新一条（窗口函数口径见
 * {@code VenuePresenceConsentRepository#countLatestByEnabledAndSource}）。
 * <p>
 * 仅记录偏好布尔值与来源，不含任何位置信息（V34 迁移头注）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_presence_consents",
        indexes = {
                @Index(name = "qwt_idx_vpcons_user_created", columnList = "userId, createdAt"),
                @Index(name = "qwt_idx_vpcons_created", columnList = "createdAt")
        })
public class VenuePresenceConsent extends BaseEntity {

    /** 用户（qwt_users.id） */
    @Column(nullable = false)
    private Long userId;

    /** 确立后的开关状态（true = 采集开启） */
    @Column(nullable = false)
    @ColumnDefault("true")
    private Boolean enabled = Boolean.TRUE;

    /** 确立来源（DEFAULT / USER） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    @ColumnDefault("'DEFAULT'")
    private ConsentSource source = ConsentSource.DEFAULT;
}
