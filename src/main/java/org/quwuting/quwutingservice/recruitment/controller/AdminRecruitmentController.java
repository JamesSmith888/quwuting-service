package org.quwuting.quwutingservice.recruitment.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.recruitment.dto.request.PublishRecruitmentRequest;
import org.quwuting.quwutingservice.recruitment.dto.request.RecruitmentSaveRequest;
import org.quwuting.quwutingservice.recruitment.dto.response.AdminRecruitmentItem;
import org.quwuting.quwutingservice.recruitment.enums.RecruitStatus;
import org.quwuting.quwutingservice.recruitment.service.RecruitmentService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店招工管理端接口（2026-08-29，docs/agents/28-recruitments.md，仅 ADMIN）。
 * <p>
 * 路由 /admin/recruitments（与 /admin/dancers 并列）。仅 GET/POST（HTTP 语义约定），
 * 编辑走 POST /{id}/update 全量覆盖，状态机 DRAFT → publish → offline →（可重发布），
 * 过期走 /renew 一键续期。发布风险词命中时 publish 返回 1010，管理员确认后
 * confirmed=true 强制放行（人工审核通道，平台直发的内审闭环）。
 */
@RestController
@RequestMapping("/admin/recruitments")
@RequiredArgsConstructor
public class AdminRecruitmentController {

    private final RecruitmentService recruitmentService;

    /**
     * 管理端列表（status 可空 = 全部；expired=true 为「已过期」视图）。
     * GET /admin/recruitments?status=&venueId=&keyword=&expired=&page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<AdminRecruitmentItem>> list(
            @RequestParam(required = false) RecruitStatus status,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.adminList(status, venueId, keyword, expired, page, size));
    }

    /**
     * 管理端详情（编辑页回显，含联系方式真实值）。
     * GET /admin/recruitments/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<AdminRecruitmentItem> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.adminDetail(id));
    }

    /**
     * 创建招工（落草稿，有效期默认 30 天）。
     * POST /admin/recruitments
     */
    @PostMapping
    public ApiResponse<AdminRecruitmentItem> create(@RequestBody RecruitmentSaveRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.create(adminId, request));
    }

    /**
     * 编辑招工（全量覆盖）。
     * POST /admin/recruitments/{id}/update
     */
    @PostMapping("/{id}/update")
    public ApiResponse<AdminRecruitmentItem> update(
            @PathVariable Long id,
            @RequestBody RecruitmentSaveRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.update(id, request));
    }

    /**
     * 发布（风险词命中且未确认时返回 1010，确认后 confirmed=true 放行）。
     * POST /admin/recruitments/{id}/publish
     */
    @PostMapping("/{id}/publish")
    public ApiResponse<AdminRecruitmentItem> publish(
            @PathVariable Long id,
            @RequestBody(required = false) PublishRecruitmentRequest request) {
        UserContext.requireAdmin();
        boolean confirmed = request != null && Boolean.TRUE.equals(request.confirmed());
        return ApiResponse.ok(recruitmentService.publish(id, confirmed));
    }

    /**
     * 手动下架。
     * POST /admin/recruitments/{id}/offline
     */
    @PostMapping("/{id}/offline")
    public ApiResponse<AdminRecruitmentItem> offline(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.offline(id));
    }

    /**
     * 一键续期（+30 天，已过期从现在起算）。
     * POST /admin/recruitments/{id}/renew
     */
    @PostMapping("/{id}/renew")
    public ApiResponse<AdminRecruitmentItem> renew(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(recruitmentService.renew(id));
    }
}
