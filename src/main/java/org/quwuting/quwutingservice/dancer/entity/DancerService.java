package org.quwuting.quwutingservice.dancer.entity;

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
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;

/**
 * 舞伴服务范围（2026-08-24 联系方式获取质量优化；2026-08-24 晚类别改版；
 * 2026-08-25 晚二轮：按时段子类别多选——sub_category 存逗号连接的枚举 code 串；
 * 2026-08-26：label 改服务端权威派生 + 新增 negotiable + 合规用词
 * （包时→按时段、线上陪聊→线上聊天、私影→影咖），qwt_dancer_services）。
 * <p>
 * 领域定位：<b>黄页内容</b>——admin 录入（平台代发模型），非用户 UGC，符合
 * 「小程序类目合规 UGC 红线」（见 AGENTS.md）。服务信息结构化展示：
 * <ul>
 *   <li>{@code category} 服务类别（PACKAGE/DANCE/ONLINE_CHAT/OTHER），
 *       需求弹层与列表「服务场景」筛选的维度；</li>
 *   <li>{@code subCategory} 按时段子类别（仅 PACKAGE 有意义，<b>逗号连接多值</b>：
 *       酒吧/舞厅/影咖/KTV/其他，如「BAR,KTV」；其余类别恒 null）；</li>
 *   <li>{@code label} 短标签——<b>服务端权威派生</b>（2026-08-26 起）：PACKAGE =
 *       子类别名顿号连接+「按时段」，DANCE/ONLINE_CHAT = 类别名，仅 OTHER =
 *       admin 手动录入的「服务内容」（如「户外露营」）——需求弹层 chip 与
 *       <b>添加好友需求消息拼接</b>的唯一文案来源；</li>
 *   <li>{@code priceText} 服务价格/计费方式（如「300元/小时起」）；</li>
 *   <li>{@code locationScope} 服务地点范围（如「5KM左右」）；</li>
 *   <li>{@code advanceNotice} 提前预约要求（如「提前 2 小时」）；</li>
 *   <li>{@code rules} 服务规则和限制（如「不含酒水」）；</li>
 *   <li>{@code negotiable} 朋友可议（2026-08-26，per-service 默认 true：
 *       朋友间价格可商量——详情页服务卡「可议」行展示；2026-08-26 合规用词：
 *       回头客/熟人可谈→朋友可议，「回头客/熟客」为陪侍黑话）。</li>
 * </ul>
 * active = 展示开关（admin 下架某服务但保留历史需求关联）；deleted = 软删。
 * sortOrder 控制详情页「服务范围」卡与需求弹层 chip 的展示顺序。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_services", indexes = {
        @Index(name = "idx_qwt_dancer_services_dancer", columnList = "dancer_id, active, sort_order")
})
public class DancerService extends BaseEntity {

    /** 所属舞伴 ID */
    @Column(nullable = false)
    private Long dancerId;

    /** 短标签（消息拼接/弹层 chip 的唯一文案来源；同舞伴下唯一；服务端按类别权威派生） */
    @Column(nullable = false, length = 20)
    private String label;

    /** 服务类别（需求弹层分组 + 列表服务场景筛选维度） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DancerServiceCategory category;

    /** 按时段子类别（2026-08-25 晚二轮多选：逗号连接的枚举 code 串，如「BAR,KTV」；
     *  仅 PACKAGE 有意义，其余类别恒 null） */
    @Column(length = 100)
    private String subCategory;

    /** 服务价格/计费方式（如「300/小时起」；空 = 面议） */
    @Column(length = 100)
    @ColumnDefault("''")
    private String priceText = "";

    /** 服务地点范围（如「本区舞厅」「可上门」；空 = 未声明） */
    @Column(length = 100)
    @ColumnDefault("''")
    private String locationScope = "";

    /** 提前预约要求（如「提前 2 小时」；空 = 未声明） */
    @Column(length = 100)
    @ColumnDefault("''")
    private String advanceNotice = "";

    /** 服务规则和限制（如「不含酒水」；空 = 未声明） */
    @Column(length = 300)
    @ColumnDefault("''")
    private String rules = "";

    /** 朋友可议（2026-08-26：per-service，默认 true——朋友间价格可商量；
     *  详情页服务卡「可议」行展示；合规用词：回头客/熟人可谈→朋友可议） */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean negotiable = true;

    /** 展示顺序（详情页服务卡与需求弹层 chip 排序） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int sortOrder = 0;

    /** 展示开关（false = 下架，不对外展示但保留历史需求关联） */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean active = true;
}
