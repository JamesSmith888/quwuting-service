package org.quwuting.quwutingservice.venuecrowd.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.response.AdminCrowdReportSummary;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端门店热度聚合接口（2026-08-29，docs/agents/27-venue-crowd-report.md，仅 ADMIN）。
 * <p>
 * 路由 /admin/crowd-reports（与 /admin/status-reports 并列）。列表公共面（详情页热度卡 +
 * 列表角标）上线后商家刷「火爆」的动机升级——本接口供运营按店查看最近 24h 上报分布
 * （档位打架 conflict / 高频修改用户 highModifyUsers 即刷量嫌疑），异常模式可见。
 * 数据量小（日活 5~36），Service 内存聚合 + 内存分页，不做 SQL 分页。
 */
@RestController
@RequestMapping("/admin/crowd-reports")
@RequiredArgsConstructor
public class AdminCrowdReportController {

    private final CrowdReportService crowdReportService;

    /**
     * 最近 24h 有热度上报的门店聚合列表（按上报条数降序，内存分页）。
     * GET /admin/crowd-reports?page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<AdminCrowdReportSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AdminCrowdReportSummary> all = crowdReportService.adminSummaries();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        Page<AdminCrowdReportSummary> result = new PageImpl<>(
                all.subList(from, to), PageRequest.of(page, size), all.size());
        return ApiResponse.ok(result);
    }
}
