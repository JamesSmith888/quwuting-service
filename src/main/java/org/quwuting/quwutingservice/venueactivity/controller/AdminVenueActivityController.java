package org.quwuting.quwutingservice.venueactivity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venueactivity.dto.request.ActivityRequest;
import org.quwuting.quwutingservice.venueactivity.dto.response.AdminVenueActivityResponse;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;
import org.quwuting.quwutingservice.venueactivity.service.VenueActivityService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店营业活动 · 管理端接口（**唯一发布通道**）。
 * <p>
 * 与门店动态域的关键差别：那边 {@code PostPublisherType} 有 {@code OWNER}
 * （门店认领人可自己发），本域**不设商家通道**——活动含优惠承诺，属经营信息，
 * 一旦门店自己发布且不兑现，投诉落小程序主体；且行业宣传语擦边概率高，平台无法
 * 事后兜底。老板把海报发给运营、运营审核结构化后录入，是唯一通道。
 * <p>
 * 全部 POST + 动词路径（项目铁律：POST + 幂等语义，禁 PUT/PATCH/DELETE）。
 */
@RestController
@RequestMapping("/admin/venue-activities")
@RequiredArgsConstructor
public class AdminVenueActivityController {

    private final VenueActivityService venueActivityService;

    @GetMapping
    public ApiResponse<Page<AdminVenueActivityResponse>> list(
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(required = false) Long venueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueActivityService.listForAdmin(status, venueId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminVenueActivityResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueActivityService.getForAdmin(id));
    }

    @PostMapping("/create")
    public ApiResponse<AdminVenueActivityResponse> create(@Valid @RequestBody ActivityRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueActivityService.create(request));
    }

    /**
     * 更新（幂等整体覆盖）。若请求带 {@code publish=true} 且活动当前非已发布，
     * 则同时完成发布——<b>重新发布是 OFFLINE 活动唯一的复活通道</b>
     * （同公告域契约，禁"改一下字段顺手复活"的隐式路径）。
     */
    @PostMapping("/{id}/update")
    public ApiResponse<AdminVenueActivityResponse> update(@PathVariable Long id,
                                                          @Valid @RequestBody ActivityRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueActivityService.update(id, request));
    }

    /** 手动下线（运营主动撤下）；有效期结束的自动下线由 30s 调度负责 */
    @PostMapping("/{id}/offline")
    public ApiResponse<AdminVenueActivityResponse> offline(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueActivityService.offline(id));
    }
}
