package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.dto.response.UserProfileResponse;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** UserPublicService 单元测试（Mockito，不依赖数据库；2026-08-12 用户公开主页） */
@ExtendWith(MockitoExtension.class)
class UserPublicServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DancerRepository dancerRepository;

    private UserPublicService userPublicService;

    @BeforeEach
    void setUp() {
        userPublicService = new UserPublicService(userRepository, dancerRepository);
    }

    /** 正常返回：公开资料 + 加入天数 + TA 创建的公开舞伴（仅 NORMAL，Repository 已过滤） */
    @Test
    void getProfile_returnsPublicProfileWithDancers() {
        User user = new User();
        user.setId(1L);
        user.setNickname("舞友甲");
        user.setRole(UserRole.USER);
        user.setCreatedAt(LocalDateTime.now().minusDays(10));
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(user));

        Dancer d1 = new Dancer();
        d1.setId(5L);
        d1.setNickname("小美");
        d1.setCity("杭州市");
        when(dancerRepository.findPublicByCreatedBy(1L)).thenReturn(List.of(d1));

        UserProfileResponse resp = userPublicService.getProfile(1L);

        assertEquals(1L, resp.id());
        assertEquals("舞友甲", resp.nickname());
        assertEquals("USER", resp.role());
        assertEquals(10, resp.joinedDays(), "加入天数 = createdAt → 今天");
        assertEquals(1, resp.dancers().size());
        assertEquals(5L, resp.dancers().get(0).id());
        assertEquals("小美", resp.dancers().get(0).nickname());
        assertEquals("杭州市", resp.dancers().get(0).city());
    }

    /** 用户不存在/已软删：1004（与 UserService.requireUser 同契约） */
    @Test
    void getProfile_userNotFound_throws() {
        when(userRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> userPublicService.getProfile(1L));
    }
}
