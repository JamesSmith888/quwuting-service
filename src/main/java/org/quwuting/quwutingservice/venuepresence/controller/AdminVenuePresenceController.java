package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端门店到访统计接口（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * 只挂统计端点，门店列表本体在 {@code AdminVenueController}（venue 域）——
 * 到访统计是挂在门店详情上的附加块，与 loadGuardState 同款「失败静默不阻断
 * 基础信息」的容错形态（admin-web 侧并行加载、统计失败不影响详情渲染）。
 */
@RestController
@RequestMapping("/admin/venues")
@RequiredArgsConstructor
public class AdminVenuePresenceController {

    private final VenuePresenceService venuePresenceService;

    /**
     * 单店到访统计（仅 ADMIN）。
     * GET /admin/venues/{venueId}/presence
     * <p>
     * 近 7 天 / 近 30 天到访人数（20m 命中口径）+ 近 30 天附近人数（300m 片区
     * 覆盖口径）+ 最近到访时刻；口径参数随响应回显（服务端权威，admin 展示必须
     * 与数值同屏）。门店不存在时由 statsFor 的查询自然返回全 0（不校验存在性——
     * 统计端点只读，无副作用）。
     */
    @GetMapping("/{venueId}/presence")
    public ApiResponse<VenuePresenceStats> stats(@PathVariable Long venueId) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.statsFor(venueId));
    }
}
