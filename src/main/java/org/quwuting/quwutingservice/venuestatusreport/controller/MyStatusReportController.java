package org.quwuting.quwutingservice.venuestatusreport.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.MyStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户级状态上报接口（「我的上报记录」）。
 * <p>
 * 路由与场所子资源 {@code /venues/{venueId}/status-reports} 区分——「我的上报记录」是
 * 用户维度资源（跨场所），不属于单个场所，参照 {@code /favorites}（用户级收藏列表）
 * 的顶层资源模型。路径取 {@code /status-reports/mine}：顶层集合 + 显式子资源，
 * 与 {@code /admin/reports} 的顶层风格一致，避免裸 {@code GET /status-reports}
 * 被误读为"所有用户的上报"。
 */
@RestController
@RequestMapping("/status-reports")
@RequiredArgsConstructor
public class MyStatusReportController {

    private final StatusReportService statusReportService;

    /**
     * 当前用户的全部状态上报记录（需登录）。
     * GET /status-reports/mine?venueId=
     * <p>
     * venueId 可选（2026-08-06）：缺省 = 跨场所全部（个人中心「我的上报」区块）；
     * 传值 = 单门店（详情页「我的上报记录」弹窗）。返回未撤销记录（含已过期），
     * 按报告时间倒序；{@code active} / {@code expiresAt} 由后端 TTL 常量计算，
     * 前端不持有 TTL 常量。
     */
    @GetMapping("/mine")
    public ApiResponse<List<MyStatusReportResponse>> listMyReports(
            @RequestParam(required = false) Long venueId
    ) {
        return ApiResponse.ok(statusReportService.listMyReports(venueId));
    }
}
