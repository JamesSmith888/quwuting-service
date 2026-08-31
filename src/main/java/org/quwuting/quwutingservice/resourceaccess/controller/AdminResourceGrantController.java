package org.quwuting.quwutingservice.resourceaccess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.request.RevokeResourceGrantRequest;
import org.quwuting.quwutingservice.resourceaccess.dto.request.UpsertResourceGrantRequest;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceGrantAuditResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceGrantResponse;
import org.quwuting.quwutingservice.resourceaccess.dto.response.ResourceSearchItemResponse;
import org.quwuting.quwutingservice.resourceaccess.enums.GrantStatus;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.service.ResourceGrantService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/resource-grants")
@RequiredArgsConstructor
public class AdminResourceGrantController {

    private final ResourceGrantService resourceGrantService;

    @GetMapping
    public ApiResponse<Page<ResourceGrantResponse>> list(
            @RequestParam(required = false) Long subjectUserId,
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) GrantStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(resourceGrantService.list(subjectUserId, resourceType, resourceId,
                status, page, size));
    }

    /**
     * 管理端资源模糊搜索（2026-08-31：新增协作页「选择门店或舞伴」数据源）。
     * 仅 ADMIN；type 必填、keyword 必填（空返回空列表）、limit 默认 20 上限 20。
     * GET /admin/resource-grants/search?type=VENUE&keyword=舞厅&limit=20
     */
    @GetMapping("/search")
    public ApiResponse<List<ResourceSearchItemResponse>> search(
            @RequestParam ResourceType type,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(resourceGrantService.searchResources(type, keyword, limit));
    }

    /**
     * 按 ID 取资源信息（2026-08-31：编辑已有协作时回显资源名）。
     * GET /admin/resource-grants/resource?type=VENUE&id=123
     */
    @GetMapping("/resource")
    public ApiResponse<ResourceSearchItemResponse> resourceInfo(
            @RequestParam ResourceType type,
            @RequestParam Long id) {
        return ApiResponse.ok(resourceGrantService.resourceInfo(type, id));
    }

    @PostMapping
    public ApiResponse<ResourceGrantResponse> upsert(@Valid @RequestBody UpsertResourceGrantRequest request) {
        return ApiResponse.ok(resourceGrantService.upsert(request));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<ResourceGrantResponse> revoke(@PathVariable Long id,
                                                     @Valid @RequestBody RevokeResourceGrantRequest request) {
        return ApiResponse.ok(resourceGrantService.revoke(id, request.reason()));
    }

    @GetMapping("/{id}/audits")
    public ApiResponse<List<ResourceGrantAuditResponse>> audits(@PathVariable Long id) {
        return ApiResponse.ok(resourceGrantService.audits(id));
    }
}