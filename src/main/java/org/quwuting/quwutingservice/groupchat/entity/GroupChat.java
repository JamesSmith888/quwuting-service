package org.quwuting.quwutingservice.groupchat.entity;

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
import org.quwuting.quwutingservice.groupchat.enums.GroupChatScope;

/**
 * 舞友群（V33 新增，运营配置的微信群聊，用户端长按二维码识别加入）。
 * <p>
 * 定位：平台运营向的引流内容实体——维度（全国/城市/地域）+ 群二维码图 +
 * 展示开关。与门店/舞伴内容解耦（群是平台维度，不绑定任何单店/单人）。
 * 公开读仅返回 {@code enabled=true AND deleted=false}；管理端可增删改查 + 上下线。
 * <p>
 * 二维码图 {@link #qrCodeUrl} 落库必须经 ImageContentValidator 内容校验
 * （08-12 安全加固约定，见 GroupChatService#create/update）；上传走
 * FileCategory.GROUP_QR（豁免二次压缩，清晰度敏感）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_group_chats", indexes = {
        @Index(name = "qwt_idx_group_chats_scope_deleted", columnList = "scope, deleted"),
        @Index(name = "qwt_idx_group_chats_city_deleted", columnList = "city, deleted")
})
public class GroupChat extends BaseEntity {

    /** 群名称（如「杭州舞友群」，用户端卡片标题） */
    @Column(nullable = false, length = 64)
    private String name;

    /** 维度（NATIONWIDE / CITY / REGION；枚举类列禁 CHECK，扩维度免迁移） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private GroupChatScope scope;

    /** 城市（scope=CITY 必填，picker region 标准行政区划名，与舞厅城市词表一致） */
    @Column(length = 50)
    private String city;

    /** 地域（scope=REGION 必填，运营自定义地域名，如「长三角」） */
    @Column(length = 50)
    private String region;

    /** 群二维码图片 URL（用户长按识别加入；管理端可随时更换——二维码 7 天有效/满员失效） */
    @Column(nullable = false, length = 500)
    private String qrCodeUrl;

    /** 群简介 / 引导语（用户端卡片副文案，可空） */
    @Column(length = 200)
    private String description;

    /** 运营排序权重（同 scope 内升序；公开列表稳定排序 = displayOrder, id） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int displayOrder = 0;

    /** 上下线开关（false = 运营临时下架，公开读不可见；区别于 deleted 软删） */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean enabled = true;

    /** 最近操作管理员（管理端创建/更新/上下线/删除记录，对齐 OpsConfig.updatedBy 先例） */
    @Column(name = "updated_by")
    private Long updatedBy;
}
