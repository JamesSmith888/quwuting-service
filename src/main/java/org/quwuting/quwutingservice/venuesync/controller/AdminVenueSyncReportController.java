package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dto.response.VenueDetailResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuesync.dto.request.ApplySyncItemRequest;
import org.quwuting.quwutingservice.venuesync.dto.request.UploadSyncReportRequest;
import org.quwuting.quwutingservice.venuesync.dto.request.VenueStatusBatchRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportDetailResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportListItemResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueStatusInfo;
import org.quwuting.quwutingservice.venuesync.service.VenueSyncReportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 门店同步管理端接口（2026-08-31，仅平台管理员）。
 * <p>
 * 数据源 = 管线（quwuting-ops/venue-opening --upload-report）上报的报告存档；
 * 调用方 = Web 管理后台（quwuting-admin-web）「门店同步」页。
 * <ul>
 *   <li>POST /admin/venue-sync/reports                 — 管线上报（幂等覆盖）</li>
 *   <li>GET  /admin/venue-sync/reports?limit=          — 历史报告列表（倒序）</li>
 *   <li>GET  /admin/venue-sync/reports/{id}            — 报告详情（统计 + 条目）</li>
 *   <li>POST /admin/venue-sync/reports/{id}/apply      — 按报告确认写库（EXACT/ALIAS）</li>
 *   <li>POST /admin/venue-sync/reports/{id}/apply-item — 单条写库（条目级「写库」按钮；
 *       仅 EXACT/ALIAS 且已匹配门店，快照+反转语义与批量一致，2026-08-31）</li>
 *   <li>GET  /admin/venue-sync/venues/{id}             — 平台门店详情（条目「平台门店」
 *       对比块查看；走 /admin 反代，不依赖小程序 /venues 前缀，2026-08-31）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venue-sync")
@RequiredArgsConstructor
public class AdminVenueSyncReportController {

    private final VenueSyncReportService reportService;
    private final VenueService venueService;

    /** 管线上报报告（幂等：同渠道同报告日覆盖） */
    @PostMapping("/reports")
    public ApiResponse<Void> upload(@Valid @RequestBody UploadSyncReportRequest request) {
        UserContext.requireAdmin();
        reportService.upload(request);
        return ApiResponse.ok(null);
    }

    /** 历史报告列表 */
    @GetMapping("/reports")
    public ApiResponse<List<SyncReportListItemResponse>> list(
            @RequestParam(defaultValue = "10") int limit) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.list(limit));
    }

    /** 报告详情 */
    @GetMapping("/reports/{id}")
    public ApiResponse<SyncReportDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.detail(id));
    }

    /** 按报告确认写库（提交 EXACT/ALIAS 条目，返回快照落库/反转统计） */
    @PostMapping("/reports/{id}/apply")
    public ApiResponse<BatchApplyResult> apply(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.apply(id));
    }

    /** 单条写库（条目级；定位 = venueId + sourceName，语义与批量 apply 一致） */
    @PostMapping("/reports/{id}/apply-item")
    public ApiResponse<BatchApplyResult> applyItem(
            @PathVariable Long id,
            @Valid @RequestBody ApplySyncItemRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.applyItem(id, request.venueId(), request.sourceName()));
    }

    /** 平台门店详情（复用小程序详情服务；走 /admin 前缀，规避 nginx 反代未覆盖 /venues） */
    @GetMapping("/venues/{id}")
    public ApiResponse<VenueDetailResponse> venueDetail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueService.getVenueDetail(id));
    }

    /** 门店搜索（映射管理「平台门店」选择器；走 /admin 前缀反代，复用列表服务） */
    @GetMapping("/venues")
    public ApiResponse<Page<VenueResponse>> searchVenues(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueService.listVenues(
                null, null, null, keyword, null, null, null, null, null, null, null, page, size));
    }

    /** 门店实时状态批量查询（条目对比条用：报告快照会过时，需平台当前状态；单次往返） */
    @PostMapping("/venues/status-batch")
    public ApiResponse<Map<Long, VenueStatusInfo>> venueStatusBatch(
            @Valid @RequestBody VenueStatusBatchRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.batchVenueStatus(request.venueIds()));
    }
}
