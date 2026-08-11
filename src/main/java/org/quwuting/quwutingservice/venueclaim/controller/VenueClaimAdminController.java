package org.quwuting.quwutingservice.venueclaim.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venueclaim.dto.request.HandleClaimRequest;
import org.quwuting.quwutingservice.venueclaim.dto.response.AdminVenueClaimResponse;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.quwuting.quwutingservice.venueclaim.service.VenueClaimService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 门店认领申请管理端接口（仅 ADMIN，2026-08-11 新增）。
 * <p>
 * 平台级聚合视图：跨场所分页列出认领申请（状态筛选），审核通过 / 拒绝。
 * 路由前缀 /admin/venue-claims 独立于用户提交通道 /venues/{venueId}/claims
 * （管理操作与具体场所无关，同 /admin/reports 模式）。
 * <p>
 * 审核通过 = 置 qwt_venues.claimed_by = 申请人 userId（A1：只能一人认领，
 * 先到先得，并发竞态在 Service 层二次校验）——申请人自动获得该店管理权，
 * 详情接口 canManage 立即重算（venue 缓存已失效）。
 */
@RestController
@RequestMapping("/admin/venue-claims")
@RequiredArgsConstructor
public class VenueClaimAdminController {

    private final VenueClaimService venueClaimService;

    /**
     * 认领申请列表（需 ADMIN）。
     * GET /admin/venue-claims?status=PENDING&page=0&size=20
     * status 可选，缺省返回全部；按提交时间倒序。
     * 管理端上下文完整返回申请材料（真实姓名/手机号/微信号/营业执照）供审核核对。
     */
    @GetMapping
    public ApiResponse<Page<AdminVenueClaimResponse>> listClaims(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(venueClaimService.listAdminClaims(status, page, size));
    }

    /**
     * 审核通过（需 ADMIN）：置门店 claimed_by = 申请人，授予管理权。
     * POST /admin/venue-claims/{id}/approve
     * body 可选：{@code {"note": "审核说明"}}。幂等：终态重复操作直接返回。
     */
    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approveClaim(@PathVariable Long id,
                                          @RequestBody(required = false) HandleClaimRequest request) {
        venueClaimService.approveClaim(id, request == null ? null : request.note());
        return ApiResponse.ok(null);
    }

    /**
     * 审核拒绝（需 ADMIN）：状态 REJECTED，申请人可再次提交新申请。
     * POST /admin/venue-claims/{id}/reject
     * body 可选：{@code {"note": "拒绝原因"}}——建议填写，随「我的认领」回传申请人。
     * 幂等：终态重复操作直接返回。
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> rejectClaim(@PathVariable Long id,
                                         @RequestBody(required = false) HandleClaimRequest request) {
        venueClaimService.rejectClaim(id, request == null ? null : request.note());
        return ApiResponse.ok(null);
    }
}
