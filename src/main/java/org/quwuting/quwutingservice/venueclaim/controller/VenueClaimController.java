package org.quwuting.quwutingservice.venueclaim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venueclaim.dto.request.CreateVenueClaimRequest;
import org.quwuting.quwutingservice.venueclaim.dto.response.VenueClaimResponse;
import org.quwuting.quwutingservice.venueclaim.service.VenueClaimService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 门店认领申请（用户侧通道，2026-08-11 新增，需求「认领舞厅」）。
 * <p>
 * 路由：提交嵌套在 /venues/{venueId}/claims（与动态 posts、状态报告
 * status-reports 等子资源保持一致的 URL 层级）；「我的认领」为用户级资源
 * 挂在 /venues/claims/mine（与 /feedbacks/mine 同模式——跨场所聚合）。
 * <p>
 * 认领<b>必须登录</b>（与上报的"匿名可提交"不同）：认领是权限授予的申请，
 * 审核通过后 userId 成为门店 claimed_by，匿名无法归属。未登录访问由
 * UserContext.requireAuth 抛 401（前端 ensureLogin 引导后重试）。
 * <p>
 * 认领审核动作（approve/reject）在 {@link VenueClaimAdminController}
 * （/admin/venue-claims）。
 */
@RestController
@RequestMapping("/venues")
@RequiredArgsConstructor
public class VenueClaimController {

    private final VenueClaimService venueClaimService;

    /**
     * 提交认领申请（需登录）。
     * POST /venues/{venueId}/claims
     * body：realName / contactPhone 必填；contactWechat / licenseUrls / note 选填。
     * 门店基础信息（名称/城市/地址）不在请求体——认领是身份归属申请而非数据
     * 编辑（见 CreateVenueClaimRequest javadoc）。
     */
    @PostMapping("/{venueId}/claims")
    public ApiResponse<VenueClaimResponse> submitClaim(
            @PathVariable Long venueId,
            @Valid @RequestBody CreateVenueClaimRequest request
    ) {
        return ApiResponse.ok(venueClaimService.submitClaim(venueId, request));
    }

    /**
     * 我的认领记录（需登录，跨场所聚合，按提交时间倒序）。
     * GET /venues/claims/mine
     * 返回全部状态记录（PENDING 待审核 / APPROVED 已通过 / REJECTED 已拒绝含
     * 原因 / WITHDRAWN 已撤回）——个人中心「我的认领」列表数据源。
     */
    @GetMapping("/claims/mine")
    public ApiResponse<List<VenueClaimResponse>> listMyClaims() {
        return ApiResponse.ok(venueClaimService.listMyClaims());
    }

    /**
     * 撤回待审核申请（需登录，本人 PENDING 可撤回）。
     * POST /venues/claims/{claimId}/withdraw
     * 撤回后状态 WITHDRAWN（终态），用户可重新提交新申请。
     */
    @PostMapping("/claims/{claimId}/withdraw")
    public ApiResponse<Void> withdrawClaim(@PathVariable Long claimId) {
        venueClaimService.withdrawClaim(claimId);
        return ApiResponse.ok(null);
    }
}
