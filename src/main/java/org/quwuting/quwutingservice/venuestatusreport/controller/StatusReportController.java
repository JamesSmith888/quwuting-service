package org.quwuting.quwutingservice.venuestatusreport.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AnnouncementSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.StatusReportListItem;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 场所突发事件（紧急公告）实时信号接口。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与动态（posts）、反馈（feedbacks）等子资源保持一致的 URL 层级。
 * 任何登录用户均可上报（不要求管理权），与 venuefeedback 的权限模型一致。
 * 2026-08-11 泛化：原「暂停营业专用」升级为 8 类突发事件（type 维度）。
 */
@RestController
@RequestMapping("/venues/{venueId}/status-reports")
@RequiredArgsConstructor
public class StatusReportController {

    private final StatusReportService statusReportService;

    /**
     * 提交或更新突发事件报告（upsert，需登录）。
     * POST /venues/{venueId}/status-reports
     * <p>
     * 请求体全部可选——极速上报时 body 可为空（{}），系统默认 type=SUSPENDED。
     * 返回更新后的活跃报告摘要。
     */
    @PostMapping
    public ApiResponse<ActiveReportSummary> submitReport(
            @PathVariable Long venueId,
            @RequestBody(required = false) SubmitReportRequest request) {
        SubmitReportRequest req = request != null ? request
                : new SubmitReportRequest(ReportType.SUSPENDED, null, null);
        return ApiResponse.ok(statusReportService.submitReport(venueId, req));
    }

    /**
     * 某门店最近突发事件列表（公开读，无需登录）。
     * GET /venues/{venueId}/status-reports
     * <p>
     * 详情页「报告突发事件」弹层默认内容：TTL 窗口内全部用户对该门店的报告，
     * 按时间倒序（最多 20 条），报告者昵称脱敏；mine 标记当前登录用户的上报。
     * 未登录访问同样可用（社区信号公开可见，与活跃报告计数口径一致）。
     */
    @GetMapping
    public ApiResponse<List<StatusReportListItem>> listRecentReports(@PathVariable Long venueId) {
        return ApiResponse.ok(statusReportService.listRecentReports(venueId));
    }

    /**
     * 某门店紧急公告区聚合（公开读，无需登录）。
     * GET /venues/{venueId}/announcements
     * <p>
     * 详情页「紧急公告」区域数据源（2026-08-11 新增）：活跃信号 + 已采纳信号按类型
     * 聚簇摘要（每类型一条：计数/已核实标记/最新时间），按严重级降序。移除信号不展示；
     * 不返回 note（审核安全约定"note 仅管理端可见"）。
     */
    @GetMapping("/announcements")
    public ApiResponse<List<AnnouncementSummary>> listAnnouncements(@PathVariable Long venueId) {
        return ApiResponse.ok(statusReportService.listAnnouncements(venueId));
    }

    /**
     * 撤销当前用户的突发事件报告（需登录）。
     * POST /venues/{venueId}/status-reports/cancel
     * <p>
     * soft delete 当前用户对该场所的报告，返回更新后的活跃报告摘要。
     */
    @PostMapping("/cancel")
    public ApiResponse<ActiveReportSummary> cancelReport(@PathVariable Long venueId) {
        return ApiResponse.ok(statusReportService.cancelReport(venueId));
    }
}
