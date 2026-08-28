package org.quwuting.quwutingservice.venuefeedback.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuefeedback.dto.request.HandleReportRequest;
import org.quwuting.quwutingservice.venuefeedback.dto.response.AdminReportResponse;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 用户上报管理端接口（仅 ADMIN）。
 * <p>
 * 平台级聚合视图：跨场所分页列出全部上报（按状态/类型筛选），
 * 处理（resolve）/ 忽略（dismiss）流转状态机。路由前缀 /admin/reports
 * 独立于 /venues/{venueId}/feedbacks（提交通道），管理操作与具体场所无关。
 */
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class ReportAdminController {

    private final VenueFeedbackService venueFeedbackService;
    private final StatusReportService statusReportService;
    private final org.quwuting.quwutingservice.appfeedback.service.AppFeedbackService appFeedbackService;

    /**
     * 上报列表（需 ADMIN）。
     * GET /admin/reports?status=PENDING&type=PRICE&page=0&size=20
     * status / type 均可选，缺省返回全部；按提交时间倒序。
     */
    @GetMapping
    public ApiResponse<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) FeedbackType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(venueFeedbackService.listAdminReports(status, type, page, size));
    }

    /**
     * 管理端上报待办总数（需 ADMIN）。
     * GET /admin/reports/pending-count
     * 轻量计数接口（首页 FAB「上报管理」菜单项红点 / 一级按钮红点聚合数据源）——
     * 与 /users/me/messages/unread-count 同模式：红点只提示"有待办"，计数随
     * 管理端处理动作自然归零；非管理员访问由 requireAdmin 拒绝（403），
     * 前端按角色门禁 + 失败静默降级处理。
     * <p>
     * 口径（2026-08-10 扩展，2026-08-28 意见反馈纳入）：<b>PENDING 门店反馈数 +
     * 活跃暂停营业上报数 + PENDING 意见反馈数</b>——「上报管理」是三类上报的
     * 统一管理入口（admin-reports 页三 tab），红点 = 任一
     * 类有待办/待巡查即亮；暂停报活跃计数经 {@link StatusReportService#countActiveReports}
     * 按 TTL 窗口计算，处置（移除）或过期后自然归零。
     */
    @GetMapping("/pending-count")
    public ApiResponse<Long> pendingCount() {
        return ApiResponse.ok(
                venueFeedbackService.countPendingReports()
                        + statusReportService.countActiveReports()
                        + appFeedbackService.countPendingFeedbacks());
    }

    /**
     * 标记上报为已处理（需 ADMIN）。
     * POST /admin/reports/{id}/resolve
     * body 可选：{@code {"note": "处理结果说明"}}——处理结果随「我的上报记录」
     * 回传上报者（2026-08-06 新增，个人中心展示）。
     * 幂等：终态（RESOLVED/DISMISSED）重复操作直接返回成功。
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolveReport(@PathVariable Long id,
                                           @RequestBody(required = false) HandleReportRequest request) {
        venueFeedbackService.resolveReport(id, request);
        return ApiResponse.ok(null);
    }

    /**
     * 采纳上报（2026-08-10 V2 新增，需 ADMIN）。
     * POST /admin/reports/{id}/adopt
     * body 可选：{@code {"note": "处理结果说明", "reward": true}}。
     * 采纳 = 管理员核实并采用该上报 → 同一事务发放积分奖励（仅登录用户；
     * 匿名上报采纳不发）。reward 缺省 / true = 采纳并奖励（终态 ADOPTED，发分）；
     * reward=false = 采纳不奖励（终态 ADOPTED_NO_REWARD，不发分）。幂等：
     * 终态重复操作直接返回成功（不重复发分）。
     */
    @PostMapping("/{id}/adopt")
    public ApiResponse<Void> adoptReport(@PathVariable Long id,
                                         @RequestBody(required = false) HandleReportRequest request) {
        venueFeedbackService.adoptReport(id, request);
        return ApiResponse.ok(null);
    }

    /**
     * 标记上报为已忽略（需 ADMIN，判定为误报/无需处理）。
     * POST /admin/reports/{id}/dismiss
     * body 可选：{@code {"note": "处理结果说明"}}。
     * 幂等：终态重复操作直接返回成功。
     */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismissReport(@PathVariable Long id,
                                           @RequestBody(required = false) HandleReportRequest request) {
        venueFeedbackService.dismissReport(id, request);
        return ApiResponse.ok(null);
    }
}
