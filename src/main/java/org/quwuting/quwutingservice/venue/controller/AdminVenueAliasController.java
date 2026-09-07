package org.quwuting.quwutingservice.venue.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dto.request.UpsertVenueAliasRequest;
import org.quwuting.quwutingservice.venue.dto.response.VenueAliasGroupResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueAliasVenueOption;
import org.quwuting.quwutingservice.venue.service.VenueAliasService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店别名管理端接口（2026-09-07 门店别名域，docs/agents/38-venue-aliases.md，仅平台管理员）。
 * <p>
 * 调用方 = Web 管理后台（quwuting-admin-web）「门店别名」页。与
 * {@code /admin/venue-sync/aliases}（同步管线的店名映射配置）语义严格分离：
 * 本组接口维护的是用户可见的门店身份属性（搜索可命中 + 详情页展示）。
 * <ul>
 *   <li>GET    /admin/venue-aliases                — 已配置别名的门店聚合列表</li>
 *   <li>GET    /admin/venue-aliases/venue-search   — 门店候选（配置时选店，keyword 可空）</li>
 *   <li>POST   /admin/venue-aliases                — 幂等 upsert（同店同名复活）</li>
 *   <li>DELETE /admin/venue-aliases/{id}           — 软删</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venue-aliases")
@RequiredArgsConstructor
public class AdminVenueAliasController {

    private final VenueAliasService aliasService;

    /** 已配置别名的门店聚合列表（组序 = 最近配置在前） */
    @GetMapping
    public ApiResponse<List<VenueAliasGroupResponse>> list() {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.list());
    }

    /** 门店候选（名称模糊，keyword 可空 = 最近收录兜底） */
    @GetMapping("/venue-search")
    public ApiResponse<List<VenueAliasVenueOption>> searchVenues(
            @RequestParam(name = "keyword", required = false) String keyword) {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.searchVenues(keyword));
    }

    /** 幂等 upsert（同店同名复活软删行） */
    @PostMapping
    public ApiResponse<VenueAliasGroupResponse.AliasItem> upsert(
            @Valid @RequestBody UpsertVenueAliasRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.upsert(request));
    }

    /** 软删别名（重配同名时复活重用） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        UserContext.requireAdmin();
        aliasService.delete(id);
        return ApiResponse.ok(null);
    }
}
