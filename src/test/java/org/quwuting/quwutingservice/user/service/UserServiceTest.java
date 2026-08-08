package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.dto.request.UpdateProfileRequest;
import org.quwuting.quwutingservice.user.dto.response.UserInfoResponse;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.mapper.UserInfoMapper;
import org.quwuting.quwutingservice.user.repository.UserRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** UserService 单元测试（Mockito，不依赖数据库） */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        // UserInfoMapper 无依赖，直接用真实实例以覆盖映射逻辑
        userService = new UserService(userRepository, new UserInfoMapper());

        user = new User();
        user.setId(1L);
        user.setOpenId("test_openid");
        user.setNickname("微信用户");
        user.setRole(UserRole.USER);
    }

    @Test
    void getUserInfo_returnsLatestUserInfo() {
        user.setRole(UserRole.ADMIN);
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));

        UserInfoResponse resp = userService.getUserInfo(1L);

        assertEquals(1L, resp.id());
        assertEquals("微信用户", resp.nickname());
        assertEquals(UserRole.ADMIN, resp.role(), "应反映数据库中的最新角色");
    }

    @Test
    void updateProfile_updatesNickname() {
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserInfoResponse resp = userService.updateProfile(
                1L, new UpdateProfileRequest("  舞友小王  ", null));

        assertEquals("舞友小王", resp.nickname(), "昵称应去除首尾空白");
        assertEquals("舞友小王", user.getNickname());
        verify(userRepository).save(user);
    }

    @Test
    void updateProfile_userNotFound_throws1004() {
        when(userRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(99L, new UpdateProfileRequest("昵称", null)));
        assertEquals(1004, ex.getCode());
    }

    @Test
    void getUserInfo_userNotFound_throws1004() {
        when(userRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.getUserInfo(99L));
        assertEquals(1004, ex.getCode());
    }
}
