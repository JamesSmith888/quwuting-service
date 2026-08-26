package org.quwuting.quwutingservice.user.mapper;

import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;
import org.quwuting.quwutingservice.user.entity.User;
import org.springframework.stereotype.Component;

/** User 实体 → UserInfoResponse 转换器（auth / user 模块共用） */
@Component
public class UserInfoMapper {

    public UserInfoResponse toResponse(User user) {
        return new UserInfoResponse(
                user.getId(),
                user.getOpenId(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getRole(),
                user.getAge(),
                user.getGender(),
                user.getCity()
        );
    }
}
