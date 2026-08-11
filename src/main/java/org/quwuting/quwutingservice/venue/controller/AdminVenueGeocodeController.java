package org.quwuting.quwutingservice.venue.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.service.GeocodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场所坐标批量补全管理端接口（仅 ADMIN，2026-08-11 新增）。
 * <p>
 * 背景：存量门店缺经纬度（前端导航 wx.openLocation 依赖），批量补齐走腾讯位置
 * 服务地理编码（输出 gcj02 与前端约定一致）。key 只放后端配置，不落前端。
 * <ul>
 *   <li>GET /admin/venues/geocode/missing-count — 缺坐标待补数量（前端按钮展示用）</li>
 *   <li>POST /admin/venues/geocode/backfill — 触发批量补全，返回处理报告（幂等可重试）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venues/geocode")
@RequiredArgsConstructor
public class AdminVenueGeocodeController {

    private final GeocodeService geocodeService;

    /** 缺坐标待补数量（需 ADMIN）。 */
    @GetMapping("/missing-count")
    public ApiResponse<Long> missingCount() {
        UserContext.requireAdmin();
        return ApiResponse.ok((long) geocodeService.countMissing());
    }

    /** 触发批量补全（需 ADMIN）。 */
    @PostMapping("/backfill")
    public ApiResponse<GeocodeService.GeocodeReport> backfill() {
        UserContext.requireAdmin();
        return ApiResponse.ok(geocodeService.backfillAll());
    }
}
