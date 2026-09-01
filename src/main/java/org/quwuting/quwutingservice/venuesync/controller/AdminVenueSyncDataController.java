package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venuesync.dto.request.BatchCreateVenueRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.BatchCreateVenueResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueExportItem;
import org.quwuting.quwutingservice.venuesync.service.VenueSyncDataService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店数据管理端接口（2026-09-01，舞讯采集 Skill/Agent 专用，仅平台管理员）。
 * <p>
 * 调用方 = 舞讯采集 Skill（WorkBuddy 技能）：Agent 先登录拿 ADMIN token，再以此组
 * 接口为数据底座完成「采集舞讯 → 比对 → 一键录入/更新状态」闭环。
 * <ul>
 *   <li>GET  /admin/venue-sync/venues/export     — 候选门店按量加载（city/status 筛选 +
 *       大页 + 精简字段；比对候选库，替代重型 listVenues）</li>
 *   <li>POST /admin/venue-sync/venues/batch-create — 批量新增门店（同城同名归一化幂等；
 *       单条失败不拖累整批；Agent 来源审计 changedBy=null）</li>
 * </ul>
 * 状态更新不在此重复实现：Skill 复用 POST /admin/venue-daily-openings/batch
 * （DailyOpeningService 权威反转语义，避免再造一套）。
 * <p>
 * 路径与 GET /admin/venue-sync/venues/{id} 并存无冲突（Spring 精确路径优先于模板，
 * 同 /venues/cities 与 /venues/{id} 共存先例）。
 */
@RestController
@RequestMapping("/admin/venue-sync/venues")
@RequiredArgsConstructor
public class AdminVenueSyncDataController {

    private final VenueSyncDataService dataService;

    /** 候选门店导出（比对数据底座）：city/status 精确筛选 + id 升序分页，size ≤ 500 */
    @GetMapping("/export")
    public ApiResponse<Page<VenueExportItem>> export(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) VenueStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dataService.exportVenues(city, status, page, size));
    }

    /** 批量新增门店（Skill「一键录入」）：同城同名幂等，单条失败不拖累整批 */
    @PostMapping("/batch-create")
    public ApiResponse<BatchCreateVenueResponse> batchCreate(
            @Valid @RequestBody BatchCreateVenueRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dataService.batchCreateVenues(request.items()));
    }
}
