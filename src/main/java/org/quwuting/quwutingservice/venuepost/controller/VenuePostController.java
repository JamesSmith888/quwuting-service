package org.quwuting.quwutingservice.venuepost.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venuepost.dto.request.CreatePostRequest;
import org.quwuting.quwutingservice.venuepost.dto.response.VenuePostResponse;
import org.quwuting.quwutingservice.venuepost.service.VenuePostService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 场所动态（公告）接口 — 公开读取，发布限管理员或门店认领人。
 */
@RestController
@RequestMapping("/venues/{venueId}/posts")
@RequiredArgsConstructor
public class VenuePostController {

    private final VenuePostService venuePostService;

    @GetMapping
    public ApiResponse<Page<VenuePostResponse>> listPosts(
            @PathVariable Long venueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(venuePostService.listPosts(venueId, page, size));
    }

    /**
     * 发布动态（管理员或门店认领人）
     * POST /venues/{venueId}/posts
     */
    @PostMapping
    public ApiResponse<VenuePostResponse> createPost(
            @PathVariable Long venueId,
            @Valid @RequestBody CreatePostRequest request
    ) {
        return ApiResponse.ok(venuePostService.createPost(venueId, request));
    }
}
