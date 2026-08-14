package org.quwuting.quwutingservice.geo.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venue.service.GeocodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逆地理编码公开接口（2026-08-14 舞伴表单"默认定位当前城市"）。
 * <p>
 * GET /geo/reverse?lat=&amp;lng= → 城市名（如"深圳市"）。
 * <ul>
 *   <li>key 只放后端配置（app.geocode.key / QQMAP_KEY），前端零硬编码
 *       （对齐"全链路 gcj02、服务商限定腾讯/高德、key 只放后端"坐标约定）；</li>
 *   <li>输入 = 前端 wx.getLocation 采集的 gcj02 坐标；输出 = 标准行政区划城市名
 *       （与 picker mode="region" 词表、列表筛选共用词表，精确匹配）；</li>
 *   <li>失败场景（未配置 key / 坐标越界 / 腾讯 API 失败）→ 业务错，前端静默降级
 *       （城市留空让用户手动选择，绝不阻塞表单主流程）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/geo")
@RequiredArgsConstructor
public class GeoController {

    private final GeocodeService geocodeService;

    /** 坐标 → 城市名（公开；城市名粗粒度低敏，不设频控，同 GET /venues/cities 策略） */
    @GetMapping("/reverse")
    public ApiResponse<String> reverse(@RequestParam double lat, @RequestParam double lng) {
        return ApiResponse.ok(geocodeService.reverseGeocode(lat, lng));
    }
}
