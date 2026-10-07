package org.quwuting.quwutingservice.venuecrowd.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.request.SubmitCrowdReportRequest;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdBaseline;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikeResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikersResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportLikeService;
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
    private final CrowdReportLikeService crowdReportLikeService;

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
     * 最近 6 小时窗口内双维（加权）中位数 + 置信度分层 + 展示文案（服务端权威）+ 折叠头摘要 headlineText；
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

    /**
     * 赞一条今晚热度上报（2026-09-03「人际认可」层，需登录；幂等——已赞返回当前态）。
     * POST /venues/{venueId}/crowd-reports/{reportId}/like
     * <p>
     * 每人每行至多 1 票（全量唯一 (liker_id, report_id)，再点取消）；仅 6h 窗口内行
     * 可赞（1019 行不存在 / 1019 归属不一致；<b>无窗口限制</b>——2026-10-07 起过期上报同样可赞）；
     * 首次赞且非自赞 → 触达上报者
     * （CROWD_REPORT_LIKED 站内信，2026-10-07 起未读合并为「收到 N 个赞」；同事务、取消再赞不重发）。
     * 响应 = 服务端权威当前态（likeCount/likedByMe），前端直接回写零拼接。赞数纯展示、永不进算法。
     */
    @PostMapping("/{reportId}/like")
    public ApiResponse<CrowdLikeResponse> like(
            @PathVariable Long venueId, @PathVariable Long reportId) {
        return ApiResponse.ok(crowdReportLikeService.like(venueId, reportId));
    }

    /**
     * 取消赞（2026-09-03，需登录；幂等——未赞过返回当前态）。
     * POST /venues/{venueId}/crowd-reports/{reportId}/unlike
     * <p>
     * 与 like 同校验（仅 6h 窗口内行）；软删 toggle OFF（对齐 qwt_favorites），
     * 取消后再赞 = 恢复原行且不重发被赞通知。
     */
    @PostMapping("/{reportId}/unlike")
    public ApiResponse<CrowdLikeResponse> unlike(
            @PathVariable Long venueId, @PathVariable Long reportId) {
        return ApiResponse.ok(crowdReportLikeService.unlike(venueId, reportId));
    }

    /**
     * 谁觉得有用（2026-10-07，公开读，软鉴权）。
     * GET /venues/{venueId}/crowd-reports/{reportId}/likers
     * <p>
     * 分层披露：上报者本人 / 管理员 ⇒ FULL（完整名单）；其他人（含未登录）⇒ SUMMARY（人数 + 分层汇总，
     * 名单为空）。不要求行在 6h 窗口内（只读）；行不存在 / 已删 / 串店 ⇒ 1019。
     */
    @GetMapping("/{reportId}/likers")
    public ApiResponse<CrowdLikersResponse> likers(
            @PathVariable Long venueId, @PathVariable Long reportId) {
        return ApiResponse.ok(crowdReportLikeService.likers(venueId, reportId));
    }

    /**
     * 常态人气（2026-10-07，公开读）：前 7 / 30 个营业日（不含今晚）的中位数按样本量诚实表达 +
     * 今晚 vs 常态的比较结论。GET /venues/{venueId}/crowd-reports/baseline
     * <p>
     * 热度页「实时人气」卡消费；详情页不消费（详情页只需折叠头摘要，已随 summary 下发）。
     */
    @GetMapping("/baseline")
    public ApiResponse<CrowdBaseline> baseline(@PathVariable Long venueId) {
        return ApiResponse.ok(crowdReportService.baseline(venueId));
    }
}
