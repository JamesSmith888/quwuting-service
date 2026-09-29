package org.quwuting.quwutingservice.venue.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dto.response.AdminVenueListItem;
import org.quwuting.quwutingservice.venue.service.AdminVenueQueryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端门店列表接口（2026-09-29，V33 到访域配套；文档 =
 * docs/agents/52-venue-presence.md §「admin 门店模块」）。
 * <p>
 * admin-web 长期缺「门店列表/详情」模块（此前只有编辑表单页），后续大量运营
 * 能力（到访统计、状态治理、资料盘点）都以它为挂载点——本接口是模块的列表
 * 数据源。单店到访统计在 {@code AdminVenuePresenceController}（详情页统计卡），
 * 门店基础信息详情复用既有公开 {@code GET /venues/{id}}（admin-web 已在消费）。
 */
@RestController
@RequestMapping("/admin/venues")
@RequiredArgsConstructor
public class AdminVenueController {

    private final AdminVenueQueryService adminVenueQueryService;

    /**
     * 管理端门店列表（仅 ADMIN；全量无业务裁剪，city/status/keyword 筛选 + 分页）。
     * GET /admin/venues?city=&status=&keyword=&page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<AdminVenueListItem>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(adminVenueQueryService.list(city, status, keyword, page, size));
    }
}
