package org.quwuting.quwutingservice.venuecrowd.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.request.SubmitCrowdReportRequest;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店热度上报接口（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 路由嵌套在 /venues/{venueId} 下，与 status-reports / feedbacks 等子资源同层级。
 * 任何登录用户可上报（快捷按钮枚举载荷，零自由文本）；聚合摘要公开读（社区信号
 * 公开可见，与门店报告同一权限模型）。不混入门店报告（突发事件语义），独立通道。
 */
@RestController
@RequestMapping("/venues/{venueId}/crowd-reports")
@RequiredArgsConstructor
public class CrowdReportController {

    private final CrowdReportService crowdReportService;

    /**
     * 提交 / 更新今晚热度（需登录，每日一记幂等 upsert）。
     * POST /venues/{venueId}/crowd-reports
     * <p>
     * 请求体：{ femaleLevel: 1-8（必填，在店舞伴档位，0-20/约30/…/约300+）,
     * maleLevel: 1-8（选填，男客数量档位，细粒度同女；缺省 = 跳过） }。
     * 返回更新后的聚合摘要（前端立即刷新展示 + mine 态 + rewardText/upgradedBadgeText
     * 即时反馈——2026-09-03「确认后积分」）。
     */
    @PostMapping
    public ApiResponse<CrowdSummary> submit(
            @PathVariable Long venueId,
            @RequestBody SubmitCrowdReportRequest request) {
        return ApiResponse.ok(crowdReportService.submit(venueId, request));
    }

    /**
     * 今晚热度聚合（公开读，无需登录）。
     * GET /venues/{venueId}/crowd-reports
     * <p>
     * 最近 6 小时窗口内双维加权众数 + 置信度分层 + 展示文案（服务端权威）；
     * 未登录 / 未上报时 mine 为 null。历史/过期记录不走本接口（见 history）。
     */
    @GetMapping
    public ApiResponse<CrowdSummary> summary(@PathVariable Long venueId) {
        return ApiResponse.ok(crowdReportService.summary(venueId));
    }

    /**
     * 全部热度历史（公开读，无需登录；2026-08-29 用户需求「用户可以看到过期后的
     * 记录」最终形态——详情页右下角「查看全部热度」链接进入独立历史页）。
     * GET /venues/{venueId}/crowd-reports/history?page=0&size=20
     * <p>
     * 分页全量（createdAt 倒序，不过滤窗口）；行内 expired = 是否已出 6h 窗口，
     * 前端仅据此派生「已过期」标签 + 置灰样式。
     */
    @GetMapping("/history")
    public ApiResponse<Page<CrowdSummary.CrowdHistoryRow>> history(
            @PathVariable Long venueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(crowdReportService.history(venueId, page, size));
    }
}
