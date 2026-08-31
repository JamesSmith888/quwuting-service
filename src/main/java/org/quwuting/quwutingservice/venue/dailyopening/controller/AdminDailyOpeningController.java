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
 * 门店每日营业快照管理端接口（仅平台管理员，2026-08-31）。
 * <p>
 * 调用方 = 每日营业信息管线（quwuting-ops/venue-opening，Agent/脚本侧）——
 * 鉴权要求 ADMIN token（不向普通用户开放；管线凭据独立管理，防 Agent 凭据泄露
 * 影响面，见 quwuting-ops README「写库鉴权」）。
 * <ul>
 *   <li>POST /admin/venue-daily-openings/batch — 批量应用当日快照（幂等）：
 *       落库快照 + 高置信反转（CEASED/SUSPENDED → OPEN，仅 EXACT/ALIAS），
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
