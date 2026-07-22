package org.quwuting.quwutingservice.favorite.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.favorite.service.FavoriteService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 获取当前用户的收藏列表
     * GET /favorites
     */
    @GetMapping
    public ApiResponse<List<VenueResponse>> getFavorites() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(favoriteService.getFavoriteVenues(userId));
    }

    /**
     * 收藏场所
     * POST /favorites/{venueId}
     */
    @PostMapping("/{venueId}")
    public ApiResponse<Void> addFavorite(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        favoriteService.addFavorite(userId, venueId);
        return ApiResponse.ok(null);
    }

    /**
     * 取消收藏
     * POST /favorites/{venueId}/remove
     */
    @PostMapping("/{venueId}/remove")
    public ApiResponse<Void> removeFavorite(@PathVariable Long venueId) {
        Long userId = UserContext.requireAuth();
        favoriteService.removeFavorite(userId, venueId);
        return ApiResponse.ok(null);
    }
}
