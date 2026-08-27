package org.quwuting.quwutingservice.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.user.enums.UserRole;

@Getter
@Setter
@Entity
@Table(name = "qwt_users", indexes = {
        @Index(name = "qwt_idx_users_open_id", columnList = "openId", unique = true)
})
public class User extends BaseEntity {

    /** 微信 openid，用户唯一标识 */
    @Column(nullable = false, length = 64, unique = true)
    private String openId;

    @Column(length = 64)
    private String nickname;

    @Column(length = 500)
    private String avatarUrl;

    /** 年龄（用户自主录入，null = 未填写；仅经 GET /users/{id} 自愿分享通道下发） */
    @Column
    private Integer age;

    /** 性别（MALE / FEMALE，null = 未声明不展示；自愿分享通道下发） */
    @Column(length = 16)
    private String gender;

    /** 常驻城市（行政区划名，如「杭州市」；自愿分享通道下发，便于同城按时段匹配） */
    @Column(length = 64)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role = UserRole.USER;
}
