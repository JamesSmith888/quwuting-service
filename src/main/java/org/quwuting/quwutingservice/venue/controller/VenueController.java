package org.quwuting.quwutingservice.venue.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dto.request.CreateVenueRequest;
import org.quwuting.quwutingservice.venue.dto.response.CityStatsResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueDetailResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venue.service.VenueViewService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;
    private final VenueHeatService venueHeatService;
    private final VenueViewService venueViewService;

    /**
     * 新增场所（仅管理员）
     * POST /venues
     */
    @PostMapping
    public ApiResponse<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueService.createVenue(request));
    }

    /**
     * 更新场所信息（管理员或门店认领人）
     * POST /venues/{id}/update
     */
    @PostMapping("/{id}/update")
    public ApiResponse<VenueResponse> updateVenue(
            @PathVariable Long id,
            @Valid @RequestBody CreateVenueRequest request
    ) {
        return ApiResponse.ok(venueService.updateVenue(id, request));
    }

    /**
     * 查询场所列表（支持按城市/区县/状态/关键字/距离半径/热门筛选，多排序方式 + 分页）
     * GET /venues?city=绍兴市&keyword=爵士&latitude=30.0&longitude=120.5&sort=recommended&radiusKm=100&hot=true&page=0&size=20
     * latitude/longitude 可选：传入后参与邻近加成排序（用户定位，gcj02）
     * sort 可选（recommended/distance/heat/newest，默认 recommended）：
     *   推荐排序（复合评分+邻近加成）/ 距离最近 / 热度最高 / 最新收录
     * radiusKm 可选（km，>0 生效）：距离半径筛选，与排序方式正交；需配合 latitude/longitude
     * window 可选（7d/30d/all，默认 7d）：卡片 Top Reaction 徽标的统计窗口（近7天/近30天/全部）
     * hot 可选（true 仅返回热门场所，2026-08-08 新增）：ID ∈ 城市内 top 20% 且 热度分 ≥ 门槛
     *   的集合（见 VenueLookupService#getHotVenueIds）；与城市/状态/距离筛选正交可叠加
     * tag 可选（2026-08-12 新增「龙女」快捷筛选）：仅返回 tags 含该标签子串的场所
     *   （如 tag=龙女 命中"龙女可进"/"龙女"标签门店，不命中"禁龙"反向标签）；与城市/状态/热门正交
     */
    @GetMapping
    public ApiResponse<Page<VenueResponse>> listVenues(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) VenueStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) Boolean hot,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(
                venueService.listVenues(city, district, status, keyword, latitude, longitude,
                        window, sort, radiusKm, hot, tag, page, size));
    }

    /**
     * 有场所的城市列表（按场所数倒序），供前端"热门城市"选择
     * GET /venues/cities
     */
    @GetMapping("/cities")
    public ApiResponse<List<CityStatsResponse>> listCities() {
        return ApiResponse.ok(venueService.listCityStats());
    }

    /**
     * 获取场所详情（含管理权限标记 canManage 与动态计数 postCount）
     * GET /venues/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<VenueDetailResponse> getVenue(@PathVariable Long id) {
        return ApiResponse.ok(venueService.getVenueDetail(id));
    }

    /**
     * 获取场所热度（综合浏览量、收藏、评价、营业稳定性等多维度）
     * GET /venues/{id}/heat
     */
    @GetMapping("/{id}/heat")
    public ApiResponse<VenueHeatResponse> getVenueHeat(@PathVariable Long id) {
        return ApiResponse.ok(venueHeatService.getHeat(id));
    }

    /**
     * 记录场所详情页浏览（软鉴权：未登录时 userId 为 null，匿名���录不去重）
     * POST /venues/{id}/view
     */
    @PostMapping("/{id}/view")
    public ApiResponse<Void> recordView(@PathVariable Long id) {
        venueViewService.recordView(id, UserContext.getCurrentUserId());
        return ApiResponse.ok(null);
    }
}
