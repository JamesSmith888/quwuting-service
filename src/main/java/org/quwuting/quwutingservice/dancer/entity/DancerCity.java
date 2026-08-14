package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 舞伴常驻城市记录（2026-08-14 多城市：一个舞伴最多 3 个城市，V29 迁移）。
 * <p>
 * 设计（见 V29 迁移注释）：
 * <ul>
 *   <li><b>子表而非逗号拼接</b>：城市是"可多值、按序、可编辑替换"的集合，
 *       子表与 DancerVenue（舞伴↔舞厅多对多）同模式，SQL 筛选/聚合直接；</li>
 *   <li><b>编辑 = 全量替换</b>：软删旧行 + 插入新行（幂等去重由 service 代码保证，
 *       与 DancerVenue 的 HOME 关系替换先例一致，无唯一约束防软删行冲突）；</li>
 *   <li><b>sortOrder</b>：维持用户选择顺序（首个 = 主城市，冗余同步 dancer.city）；</li>
 *   <li>遵循项目"无 JPA 关系、纯 Long 列 + 索引"的既有模式（同 DancerVenue / Favorite）。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_cities", indexes = {
        @Index(name = "qwt_idx_dancer_cities_dancer", columnList = "dancerId"),
        @Index(name = "qwt_idx_dancer_cities_city", columnList = "city")
})
public class DancerCity extends BaseEntity {

    @Column(nullable = false)
    private Long dancerId;

    /** 城市名（picker mode="region" 标准行政区划名，与 dancer.city / 列表筛选共用词表） */
    @Column(nullable = false, length = 50)
    private String city;

    /** 展示顺序（0 起始；首个 = 主城市） */
    @Column(nullable = false)
    private int sortOrder;
}
