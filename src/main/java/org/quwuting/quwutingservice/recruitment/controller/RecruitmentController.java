package org.quwuting.quwutingservice.recruitment.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentContactResponse;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentDetail;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentListItem;
import org.quwuting.quwutingservice.recruitment.service.RecruitmentService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店招工用户侧接口（2026-08-29，docs/agents/28-recruitments.md）。
 * <p>
 * 路由 /recruitments（复数名词 kebab-case）。列表与详情公开读（黄页信息展示，
 * 与门店列表同一权限模型）；联系方式获取需登录。内容仅管理员直发，本控制器
 * 无任何写入通道。对外文案统一「招工/急聘」，规避「招聘」类目机审词族。
 */
@RestController
@RequestMapping("/recruitments")
@RequiredArgsConstructor
public class RecruitmentController {

    private final RecruitmentService recruitmentService;

    /**
     * 招工列表（公开读）：急聘置顶 + 发布时间倒序，过期硬过滤。
     * GET /recruitments?city=&venueId=&page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<RecruitmentListItem>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Long venueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(recruitmentService.list(city, venueId, page, size));
    }

    /**
     * 招工详情（公开读）：联系方式恒不下发，hasContact 驱动「获取联系方式」入口。
     * GET /recruitments/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<RecruitmentDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(recruitmentService.detail(id));
    }

    /**
     * 获取联系方式（需登录）：幂等留痕后实时返回真实值，前端据此渲染拨打/复制。
     * POST /recruitments/{id}/contact
     */
    @PostMapping("/{id}/contact")
    public ApiResponse<RecruitmentContactResponse> contact(@PathVariable Long id) {
        return ApiResponse.ok(recruitmentService.fetchContact(id));
    }
}
