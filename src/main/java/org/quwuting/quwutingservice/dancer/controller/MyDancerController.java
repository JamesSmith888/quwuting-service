package org.quwuting.quwutingservice.dancer.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerSummaryResponse;
import org.quwuting.quwutingservice.dancer.dto.response.MyDancerRecognitionResponse;
import org.quwuting.quwutingservice.dancer.service.DancerService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人中心舞伴相关接口（用户级资源，需登录）。
 * <ul>
 *   <li>GET /users/me/dancer-recognitions — 我的认可记录（"我认可过谁"回顾视图）</li>
 *   <li>GET /users/me/dancers — 我的舞伴主页（创建人视角，含 PENDING/HIDDEN 自有资料）</li>
 * </ul>
 */
@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class MyDancerController {

    private final DancerService dancerService;

    /** 我的认可记录（需登录；按最近认可时间倒序，同舞伴只取最近一条） */
    @GetMapping("/dancer-recognitions")
    public ApiResponse<List<MyDancerRecognitionResponse>> myRecognitions() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.listMyRecognitions(userId));
    }

    /** 我的舞伴主页列表（需登录；创建人视角，status 由前端渲染审核中/已隐藏徽标） */
    @GetMapping("/dancers")
    public ApiResponse<List<DancerSummaryResponse>> myDancers() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.listMyDancers(userId));
    }
}
