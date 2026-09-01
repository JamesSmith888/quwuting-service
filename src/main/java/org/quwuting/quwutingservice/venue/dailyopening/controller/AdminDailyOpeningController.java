package org.quwuting.quwutingservice.venue.dailyopening.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dailyopening.service.DailyOpeningService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店状态反转管理端接口（仅平台管理员，2026-08-31；2026-09-01 快照机制退出后仅反转）。
 * <p>
 * 调用方 = 门店同步管线（quwuting-ops/venue-opening，Agent/脚本侧）——
 * 鉴权要求 ADMIN token（不向普通用户开放；管线凭据独立管理，防 Agent 凭据泄露
 * 影响面，见 quwuting-ops README「写库鉴权」）。
 * <ul>
 *   <li>POST /admin/venue-daily-openings/batch — 批量执行状态反转：资讯 OPEN +
 *       平台 CEASED/SUSPENDED 且来源可信（EXACT/ALIAS 自动 / forceReversal 人工
 *       放行）→ 反转为 OPEN（单向，不会把营业中的门店标停业）；不再落每日快照，
 *       返回统计与反转明细（审计/回滚依据）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venue-daily-openings")
@RequiredArgsConstructor
public class AdminDailyOpeningController {

    private final DailyOpeningService dailyOpeningService;

    @PostMapping("/batch")
    public ApiResponse<BatchApplyResult> applyBatch(
            @Valid @RequestBody ApplyDailyOpeningBatchRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(dailyOpeningService.applyBatch(request));
    }
}
