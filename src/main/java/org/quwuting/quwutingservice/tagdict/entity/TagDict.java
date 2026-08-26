package org.quwuting.quwutingservice.tagdict.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 通用标签字典条目（2026-08-24）。
 * <p>
 * 舞伴资料标签（线上/线下/龙女…）的权威定义：text = 展示名（标签 chip 文案）、
 * description = 说明（用户长按/点击标签弹层的权威文案——「龙女」的尊重说明即存于此）、
 * scope = 适用领域（DANCER / VENUE，见 {@code TagScope}）。管理员可新增（低频管理操作）。
 * <p>
 * 关联方（qwt_dancers.profile_tags）存<b>字典 id 数组</b>（JSON，如 [1,3]）而非 text——
 * 标签重命名/改说明不影响历史关联（对比门店「存 text」方案无法重命名）。停用标签
 * （active=false）不再出现在编辑页可选列表，但历史关联仍随 resolveByIds 下发展示。
 * <p>
 * 与「舞伴认可标签」（DancerRecognitionTag，用户行为产生）语义完全独立：
 * 本实体 = 管理员/运营设置的资料标签（黄页内容，平台代发模型，无 UGC 红线风险）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_tag_dict", indexes = {
        @Index(name = "idx_qwt_tag_dict_scope", columnList = "scope, active, sortOrder")
})
public class TagDict extends BaseEntity {

    /** 适用领域（DANCER 舞伴 / VENUE 门店预留） */
    @Column(nullable = false, length = 20)
    private String scope;

    /** 展示名（标签 chip 文案，1-20 字符；同 scope 下唯一） */
    @Column(nullable = false, length = 20)
    private String text;

    /** 说明文案（长按/点击标签弹层内容；空串 = 无说明不弹）。列默认值唯一声明通道 = @ColumnDefault */
    @Column(nullable = false, length = 300)
    @ColumnDefault("''")
    private String description = "";

    /**
     * 展示配色（2026-08-26，标签级配色）：hex 展示色（如 #E63946），
     * 详情/列表的 profileTags chip 背景 = 本值 + 按亮度算对比文字色；
     * NULL/空串 = 默认样式（前端不渲染彩色 chip）。存标签字典而非「舞伴×标签」
     * 关联——同一标签在所有舞伴处同色（跨舞伴视觉一致）。
     */
    @Column(length = 20)
    private String color;

    /** 展示排序（小在前；编辑页可选列表按此排序） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int sortOrder = 0;

    /** 是否启用（停用后不出现在可选列表，历史关联保留展示）。列默认值唯一声明通道 = @ColumnDefault */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean active = true;

    /** 创建人用户 ID（新增标签的管理员） */
    private Long createdBy;
}
