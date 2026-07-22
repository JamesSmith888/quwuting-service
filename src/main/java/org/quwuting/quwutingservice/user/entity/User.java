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

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role = UserRole.USER;
}
