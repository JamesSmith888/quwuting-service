package org.quwuting.quwutingservice.user.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.dto.response.AdminUserDetailResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserItem;
import org.quwuting.quwutingservice.user.dto.response.AdminUserStatsResponse;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.enums.UserSortMode;
import org.quwuting.quwutingservice.user.service.AdminUserService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户接口（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md；
 * 仅 ADMIN；2026-08-27 用户管理增强：筛选/排序/统计概览/全维度详情）。
 * <p>
 * 定位：运营查用户/看贡献/识别异常的<b>完整工作台</b>——列表（昵称搜索 +
 * 角色/城市筛选 + 排序模式 + 行为信号行）+ 统计概览（总用户/今日新增/管理员/
 * 近 7 日活跃）+ 详情（完整画像：资料 + 积分收支 + 贡献 + 需求/上报/认领分布 +
 * 打卡连续性）。展示边界 = 管理端（requireAdmin）；<b>不建公开用户主页</b>
 * （2026-08-21 用户公开主页因审核驳回下线，见 AGENTS.md「小程序类目合规
 * UGC 红线」）。
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
     * 用户分页列表（GET /admin/users?page=&size=&keyword=&role=&city=&sort=）。
     * keyword = 昵称模糊；role = 角色筛选（ADMIN/USER）；city = 城市精确匹配；
     * sort = 排序模式（LATEST_JOINED 默认 / POINTS_DESC / LAST_ACTIVE_DESC）；
     * 全部可空/缺省。仅 ADMIN（requireAdmin）。
     */
    @GetMapping
    public ApiResponse<Page<AdminUserItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) UserSortMode sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.list(keyword, role, city, sort, page, size));
    }

    /**
     * 用户统计概览（GET /admin/users/stats，仅 ADMIN）：列表页顶部统计条——
     * 总用户 / 今日新增 / 管理员 / 近 7 日活跃。
     */
    @GetMapping("/stats")
    public ApiResponse<AdminUserStatsResponse> stats() {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.stats());
    }

    /**
     * 用户详情（GET /admin/users/{id}；仅 ADMIN）：管理端列表行点击 → 用户详情——
     * 公开资料 + 积分账户收支 + 贡献档案完整明细 + 需求/上报/认领分布 + 打卡
     * 连续性（完整画像）。openId 等敏感字段绝不下发；用户不存在/已软删 → 1004。
     */
    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminUserService.detail(id));
    }
}
