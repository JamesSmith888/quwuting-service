package org.quwuting.quwutingservice.venuepresence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;

/**
 * 到店足迹状态确立流水（2026-09-29 四轮，V34；2026-10-03 五轮「到店首问」；文档 =
 * docs/agents/52-venue-presence.md §5）。
 * <p>
 * 一行 = 一次「状态确立」，来源见 {@link ConsentSource}：PROMPT = 到店首问回答；USER = 「我的-设置」
 * 手动拨动开关；DEFAULT = 历史行（09-29 ~ 10-03 默认开启期首次 ping 时补记，已停止写入）。
 * 当前态 = 每用户最新一条，同时是<b>采集门禁</b>的判据（{@code VenuePresenceService#isExplicitlyEnabled}）。
 * <p>
 * ⚠️ 字段<b>刻意无 Java 默认值</b>（2026-10-03 移除 {@code enabled = TRUE} / {@code source = DEFAULT}）：
 * 本表是同意证据，任何写入路径漏设字段都必须撞上 NOT NULL 显式失败，而不是被静默补成「开启 / 默认」——
 * 那等于替用户同意。V34 两列本就无 DB DEFAULT，原 {@code @ColumnDefault} 注解与库表不符一并删除。
 * 这是 14 号「NOT NULL 列必带 {@code @ColumnDefault}」约定的<b>有意例外</b>：该约定保护的是「对存量表
 * ADD COLUMN」路径，两列随建表产生、不存在该路径；而同意证据上的默认值恰恰是要禁止的东西。
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
    private Boolean enabled;

    /** 确立来源（PROMPT / USER；DEFAULT 仅存在于历史行） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private ConsentSource source;
}
