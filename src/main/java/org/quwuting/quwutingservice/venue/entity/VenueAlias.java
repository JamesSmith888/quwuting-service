package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 门店别名（qwt_venue_aliases，2026-09-07，MySQL V12）。
 * <p>
 * 管理员维护的门店「用户可见别名」（曾用名/俗称/圈内涵称）——舞厅常改名，
 * 用户只记得老名字时靠别名搜索认店（{@code VenueRepository#KW_MATCH} 命中通道）、
 * 在详情页核对身份（{@code VenueDetailResponse#aliases} 展示）。
 * <p>
 * 与 {@code VenueSyncAlias}（qwt_venue_sync_aliases，同步管线的「信息源店名 →
 * 平台门店」映射，导出 aliases.json 给 matcher 消费）语义严格分离：
 * <ul>
 *   <li>sync alias = 管线匹配配置，key=(city, source_name)，用户不可见；</li>
 *   <li>本表 = 门店身份属性，搜索可命中 + 详情页展示。</li>
 * </ul>
 * 幂等：同店同名至多一条有效记录（生成列部分唯一索引，MySQL V12）；删除走软删，
 * 重新添加同名时复活重用（{@code VenueAliasService#upsert}）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_aliases", indexes = {
        @Index(name = "qwt_idx_venue_aliases_venue", columnList = "venueId")
})
public class VenueAlias extends BaseEntity {

    /** 门店 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 别名（曾用名/俗称/圈内涵称），字面子串匹配口径参与搜索 */
    @Column(nullable = false, length = 100)
    private String alias;
}
