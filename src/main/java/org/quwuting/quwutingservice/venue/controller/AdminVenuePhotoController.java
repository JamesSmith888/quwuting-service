package org.quwuting.quwutingservice.venue.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dto.request.UpdateVenuePhotoStatusRequest;
import org.quwuting.quwutingservice.venue.dto.response.AdminVenuePhotoResponse;
import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 门店管理端接口（仅平台管理员）——门店相册照片审核（2026-08-20 门店照片域）。
 * <ul>
 *   <li>GET /admin/venues/photos — 门店照片审核列表（status 可选，按上传时间倒序）</li>
 *   <li>POST /admin/venues/photos/{photoId}/status — 照片审核（PENDING → PUBLIC / REJECTED，
 *       reason 可选仅审计日志；POST 动作路径符合「只允许 GET 和 POST」约定）</li>
 * </ul>
 * 背景（AGENTS.md「门店照片域」）：普通用户（UGC）上传的门店照片先审后发——本接口
 * 是"先审后发"审核闸门的管理员侧落点，与舞伴照片审核（AdminDancerController
 * /admin/dancers/photos）同构；门店管理方（认领人/管理员）上传直发 PUBLIC 不走本闸门。
 */
@RestController
@RequestMapping("/admin/venues/photos")
@RequiredArgsConstructor
public class AdminVenuePhotoController {

    private final VenueService venueService;

    /**
     * 门店照片审核列表（仅 ADMIN，分页倒序）。status 可选（PENDING / PUBLIC / REJECTED），
     * 缺省返回全部——管理员从「待审核」筛选进入待办，可切换查看已处理历史。
     */
    @GetMapping
    public ApiResponse<Page<AdminVenuePhotoResponse>> list(
            @RequestParam(required = false) VenuePhotoStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueService.listAdminPhotos(status, page, size));
    }

    /**
     * 门店照片审核（仅 ADMIN）：PENDING → PUBLIC（通过，公开）/ PENDING → REJECTED
     * （驳回，reason 可选审计）。已审核照片重复提交幂等返回。
     */
    @PostMapping("/{photoId}/status")
    public ApiResponse<Void> updatePhotoStatus(@PathVariable Long photoId,
                                               @Valid @RequestBody UpdateVenuePhotoStatusRequest request) {
        Long adminId = UserContext.requireAdmin();
        venueService.updateVenuePhotoStatus(adminId, photoId, request.status(), request.reason());
        return ApiResponse.ok(null);
    }
}
