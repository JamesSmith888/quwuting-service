package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorPage;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;
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
    /** 展示口径唯一出口（C 侧文案 + admin 足迹各面，2026-10-13）：统计 / 名单都经它取，⛔ 不直连事实层另算 */
    private final VenueVisitBadgeService venueVisitBadgeService;

    /**
     * 单店足迹统计（仅 ADMIN）。
     * GET /admin/venues/{venueId}/presence
     * <p>
     * 近 7 / 30 天附近足迹人数 + 近 30 天到访次数 + 最近足迹时刻 + 小程序卡片此刻显示的原文 + 邻域内的其他门店。
     * <b>与小程序列表卡片、admin 门店列表行读同一次计算</b>（2026-10-13，{@link VenueVisitBadgeService}）；
     * 口径参数（取数半径 / 邻域半径 / 精度门槛）随响应回显（服务端权威，admin 展示必须与数值同屏）。
     * 门店不存在时查询自然返回全 0（不校验存在性——统计端点只读，无副作用）。
     */
    @GetMapping("/{venueId}/presence")
    public ApiResponse<VenuePresenceStats> stats(@PathVariable Long venueId) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueVisitBadgeService.adminStatsOf(venueId));
    }

    /**
     * 单店足迹用户名单（仅 ADMIN，2026-10-06 名单下钻第一级；2026-10-13 并入展示口径一源）。
     * GET /admin/venues/{venueId}/visitors?windowDays=30&page=0&size=20
     * <p>
     * 把详情页 / 列表行上的聚合数字展开成具体的人（头像 / 昵称 / 代号 / 到访次数 / 最近足迹时刻，
     * 内部账号打标签）。总人数与 {@link #stats} <b>同窗口下逐数相等</b>（同一次计算，服务端保证）：
     * {@code windowDays=7} ↔ 卡片「7 天」，{@code windowDays=30} ↔ 卡片「30 天」及「N 次」——
     * ⛔ 前端不得用本地过滤近似这个名单。
     * <p>
     * {@code windowDays} 缺省 30；服务端钳制到 [1, 3650]（不再钳到 ≥30：7 天也是可点的数字）。
     * 口径参数随响应回显，admin 端必须与数值同屏。
     */
    @GetMapping("/{venueId}/visitors")
    public ApiResponse<AdminVenueVisitorPage> visitors(
            @PathVariable Long venueId,
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueVisitBadgeService.adminVisitorsOf(
                venueId, windowDays == null ? 30 : windowDays, page, size));
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
