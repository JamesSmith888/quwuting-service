package org.quwuting.quwutingservice.announcement.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementDetailResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementSummaryResponse;
import org.quwuting.quwutingservice.announcement.service.AnnouncementService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局公告用户端接口（2026-09-01，docs/agents/34，需登录）。
 * <p>
 * 小程序消费面：首页公告条 / 公告中心列表 / 详情页（towxml 渲染 markdown）。
 * 已读机制 = 回执表（用户 × 公告唯一），详情打开后前端调 read 幂等标记。
 * 管理端（发布/下线/统计）在 {@link AdminAnnouncementController}。
 */
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 公告列表（分页倒序，pinned 优先；read 布尔已按当前用户派生） */
    @GetMapping
    public ApiResponse<Page<AnnouncementSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(announcementService.listVisible(userId, page, size));
    }

    /** 未读公告数（首页公告条 / 我的页入口红点数据源） */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(announcementService.unreadCount(userId));
    }

    /** 公告详情（markdown 原文；已下线/已删 → 404） */
    @GetMapping("/{id}")
    public ApiResponse<AnnouncementDetailResponse> detail(@PathVariable Long id) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(announcementService.detail(userId, id));
    }

    /** 标记已读（幂等；详情页打开即调） */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        Long userId = UserContext.requireAuth();
        announcementService.markRead(userId, id);
        return ApiResponse.ok(null);
    }
}
