package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorPage;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * 近 7 天 / 近 30 天到访人数（命中口径 HIT_RADIUS_M，同址门店按营业状态归因、无法归因时共享）+
     * 近 30 天附近人数（300m 片区覆盖口径）+ 最近到访时刻 + 同址归因方式；口径参数随响应回显
     * （服务端权威，admin 展示必须与数值同屏）。门店不存在时由 statsFor 的查询自然返回全 0
     * （不校验存在性——统计端点只读，无副作用）。
     */
    @GetMapping("/{venueId}/presence")
    public ApiResponse<VenuePresenceStats> stats(@PathVariable Long venueId) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.statsFor(venueId));
    }

    /**
     * 单店到访用户名单（仅 ADMIN，2026-10-06 名单下钻第一级）。
     * GET /admin/venues/{venueId}/visitors?windowDays=30&page=0&size=20
     * <p>
     * 把行内聚合数字下钻到具体的人（头像 / 昵称 / 代号 / 到访次数 / 最近到访时刻，
     * 内部账号打标签而非排除）。人数与 {@link #stats} 的 30 天口径<b>逐人相等</b>
     * （同一次归因与并集计算，服务端保证）——⛔ 前端不得用本地过滤近似这个名单。
     * <p>
     * {@code windowDays} 缺省 30（= 列表行的 30 天，点进去看到的总数就是列表写的人数），
     * 可放大查历史，但被钳到 ≥ 30（小于 30 会与列表行冲突）。
     * 口径参数（windowDays / hitRadiusM / coLocated*）随响应回显，admin 端必须与数值同屏。
     */
    @GetMapping("/{venueId}/visitors")
    public ApiResponse<AdminVenueVisitorPage> visitors(
            @PathVariable Long venueId,
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.visitorsFor(
                venueId, windowDays == null ? 0 : windowDays, page, size));
    }

    /**
     * 到店足迹开关统计（仅 ADMIN，2026-09-29 四轮 V34；2026-10-03 五轮改到店首问口径）。
     * GET /admin/venues/presence-consent-stats
     * <p>
     * 当前态分布（每用户最新一条 consent 行）：已允许 / 已关闭 / 待补问（最新态仍是默认开启期的
     * 历史 DEFAULT）+ 到店首问回答分布 + 近 30 天设置变更次数。全局口径（非门店维度），
     * admin-web 门店列表页头展示。
     */
    @GetMapping("/presence-consent-stats")
    public ApiResponse<VenuePresenceConsentStats> consentStats() {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.consentStats());
    }
}
