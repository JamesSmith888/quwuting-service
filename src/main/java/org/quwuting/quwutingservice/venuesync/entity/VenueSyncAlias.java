package org.quwuting.quwutingservice.venuesync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 门店同步 · 手动映射别名（qwt_venue_sync_aliases，2026-08-31，V64）。
 * <p>
 * 管理员在 Web 管理后台「门店同步 → 映射管理」手工配置的
 * 「网上门店名称（信息源店名）→ 平台门店」映射，等价于管线 Matcher 的
 * 别名表（ALIAS 置信度命中）。管线 {@code --refresh-aliases} 从后端拉取
 * （GET /admin/venue-sync/aliases/export）写入本地 data/aliases.json。
 * <ul>
 *   <li>key = (city, source_name)：城市用标准城市名（对齐 cities.json 口径，
 *       如「成都市」），source_name 为信息源清洗后店名（对齐报告 source_name）；</li>
 *   <li>venueId 指向 qwt_venues.id，配置时后端校验门店存在；</li>
 *   <li>幂等：同城同名至多一条（部分唯一索引，软删后可重插）。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_sync_aliases", indexes = {
        @Index(name = "qwt_idx_sync_aliases_updated", columnList = "updatedAt")
})
public class VenueSyncAlias extends BaseEntity {

    /** 标准城市名（对齐管线 cities.json 口径，如「成都市」） */
    @Column(nullable = false, length = 50)
    private String city;

    /** 网上门店名称（信息源清洗后店名，对齐报告 source_name） */
    @Column(nullable = false, length = 100)
    private String sourceName;

    /** 平台门店 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 备注（选填，如配置来源/理由） */
    @Column(nullable = false, length = 200)
    private String note = "";
}
