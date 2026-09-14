package org.quwuting.quwutingservice.spend.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageUserItem;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUserEntriesResponse;
import org.quwuting.quwutingservice.spend.service.AdminSpendStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端「计时器 & 计时账本使用情况」统计接口（2026-09-14，
 * docs/agents/35-dashboard-stats.md；仅 ADMIN 消费）。
 * <p>
 * 只读统计，挂在 /admin/** 下（admin-web 反代白名单既有前缀，无需改
 * vite proxy / nginx 模板）；与大盘 /admin/users/daily-stats 同款鉴权与
 * 窗口钳制。注意：<b>MySQL 8 方言</b>（生产 RDS MySQL），PG 环境不可执行。
 */
@RestController
@RequestMapping("/admin/spend")
@RequiredArgsConstructor
public class AdminSpendStatsController {

    private final AdminSpendStatsService adminSpendStatsService;

    /**
     * 计时器 & 账本使用统计（GET /admin/spend/usage-stats?days=30，仅 ADMIN）：
     * 汇总（记账用户/计时用户/近7日活跃/条目/收支）+ 近 N 天按日序列（计时结算/
     * 手动补记/活跃记账用户，含今日骨架补零）+ 支出分类分布 + 关联门店 TOP，
     * 一次 DB 往返族返回全部序列。口径权威见 {@link AdminSpendStatsService} 与
     * {@code SpendStatsRepository}。
     */
    @GetMapping("/usage-stats")
    public ApiResponse<AdminSpendUsageStatsResponse> usageStats(
            @RequestParam(defaultValue = "30") int days) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminSpendStatsService.usageStats(days));
    }

    /**
     * 记账用户列表（GET /admin/spend/usage-users，仅 ADMIN）：有账目的真实用户
     * （同口径剔 ADMIN / test_ / wechat_review）按最近记账降序，逐用户聚合
     * 笔数 / 计时结算 / 手动 / 收支——admin-web「记账用户」列表数据源，
     * 行点击复用资料协作 user-detail 路由下钻。
     */
    @GetMapping("/usage-users")
    public ApiResponse<List<AdminSpendUsageUserItem>> usageUsers() {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminSpendStatsService.usageUsers());
    }

    /**
     * 单用户计时/记账流水（GET /admin/spend/users/{userId}/entries?limit=50，
     * 仅 ADMIN）：用户详情「计时 · 账本」卡片数据源——summary 全量汇总 +
     * 最近流水（只回未软删，口径与统计一致）。
     */
    @GetMapping("/users/{userId}/entries")
    public ApiResponse<AdminSpendUserEntriesResponse> userEntries(
            @PathVariable long userId,
            @RequestParam(defaultValue = "50") int limit) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminSpendStatsService.userEntries(userId, limit));
    }
}
