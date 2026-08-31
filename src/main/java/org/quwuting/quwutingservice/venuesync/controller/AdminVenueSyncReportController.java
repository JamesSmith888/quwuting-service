package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venuesync.dto.request.UploadSyncReportRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportDetailResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportListItemResponse;
import org.quwuting.quwutingservice.venuesync.service.VenueSyncReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店同步报告管理端接口（2026-08-31，仅平台管理员）。
 * <p>
 * 数据源 = 管线（quwuting-ops/venue-opening --upload-report）上报的报告存档；
 * 调用方 = Web 管理后台（quwuting-admin-web）「门店同步」页。
 * <ul>
 *   <li>POST /admin/venue-sync/reports          — 管线上报（幂等覆盖）</li>
 *   <li>GET  /admin/venue-sync/reports?limit=   — 历史报告列表（倒序）</li>
 *   <li>GET  /admin/venue-sync/reports/{id}     — 报告详情（统计 + 条目）</li>
 *   <li>POST /admin/venue-sync/reports/{id}/apply — 按报告确认写库（EXACT/ALIAS）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venue-sync/reports")
@RequiredArgsConstructor
public class AdminVenueSyncReportController {

    private final VenueSyncReportService reportService;

    /** 管线上报报告（幂等：同渠道同报告日覆盖） */
    @PostMapping
    public ApiResponse<Void> upload(@Valid @RequestBody UploadSyncReportRequest request) {
        UserContext.requireAdmin();
        reportService.upload(request);
        return ApiResponse.ok(null);
    }

    /** 历史报告列表 */
    @GetMapping
    public ApiResponse<List<SyncReportListItemResponse>> list(
            @RequestParam(defaultValue = "10") int limit) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.list(limit));
    }

    /** 报告详情 */
    @GetMapping("/{id}")
    public ApiResponse<SyncReportDetailResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.detail(id));
    }

    /** 按报告确认写库（提交 EXACT/ALIAS 条目，返回快照落库/反转统计） */
    @PostMapping("/{id}/apply")
    public ApiResponse<BatchApplyResult> apply(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(reportService.apply(id));
    }
}
