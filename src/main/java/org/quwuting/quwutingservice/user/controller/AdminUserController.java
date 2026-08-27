package org.quwuting.quwutingservice.user.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.dto.response.AdminUserDetailResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserItem;
import org.quwuting.quwutingservice.user.service.AdminUserService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户列表接口（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md；
 * 仅 ADMIN）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的列表——行含用户公开资料（昵称/头像/角色/
 * 加入天数）+ 积分余额 + 贡献档案摘要（贡献值 + 等级称号）。
 * 展示边界 = 管理端（requireAdmin）；<b>不建公开用户主页</b>（2026-08-21 用户公开
 * 主页因审核驳回下线，见 AGENTS.md「小程序类目合规 UGC 红线」）。
 * <p>
 * 封禁等风控操作不在本期（需完整风控设计：封禁语义/解封/申诉/登录拦截，见
 * 23 号文档「后续规划」）——软删会被登录自动重建绕过（AuthService
 * findByOpenIdAndDeletedFalse → orElseGet createUser），不可当封禁用。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 用户分页列表（GET /admin/users?page=&size=&keyword=）。
     * keyword = 昵称模糊（空/缺省 = 全部，id 倒序）；仅 ADMIN（requireAdmin）。
     */
    @GetMapping
    public ApiResponse<Page<AdminUserItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.list(keyword, page, size));
    }

    /**
     * 用户详情（GET /admin/users/{id}；仅 ADMIN）：管理端列表行点击 → 用户详情——
     * 公开资料 + 积分余额 + <b>贡献档案完整明细</b>（等级 + 各维度计数）。
     * openId 等敏感字段绝不下发；用户不存在/已软删 → 1004。
     */
    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.detail(id));
    }
}
