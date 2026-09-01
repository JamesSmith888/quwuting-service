package org.quwuting.quwutingservice.announcement.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.dto.request.CreateAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.request.PublishAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.request.UpdateAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.response.AdminAnnouncementResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementStatsResponse;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.service.AnnouncementService;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局公告管理端接口（2026-09-01，docs/agents/34，需 ADMIN）。
 * <p>
 * 管理面在 Web 管理后台（quwuting-admin-web「公告管理」入口）；遵守项目 HTTP
 * 约定仅 GET/POST（写操作一律 POST action 风格，禁 PUT/PATCH/DELETE）。
 * 状态机：DRAFT → PUBLISHED（立即/定时）→ OFFLINE；任意态软删除。
 */
@RestController
@RequestMapping("/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    /** 公告列表（状态/分类/来源筛选 + 分页，id 倒序） */
    @GetMapping
    public ApiResponse<Page<AdminAnnouncementResponse>> list(
            @RequestParam(required = false) AnnouncementStatus status,
            @RequestParam(required = false) AnnouncementCategory category,
            @RequestParam(required = false) AnnouncementSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.adminList(status, category, source, page, size));
    }

    /** 公告详情 / 编辑回显 */
    @GetMapping("/{id}")
    public ApiResponse<AdminAnnouncementResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.adminDetail(id));
    }

    /** 创建公告（存草稿；source 固定 MANUAL） */
    @PostMapping("/create")
    public ApiResponse<AdminAnnouncementResponse> create(
            @Valid @RequestBody CreateAnnouncementRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.create(request, adminId));
    }

    /** 更新公告（DRAFT 全字段；PUBLISHED 仅追加正文） */
    @PostMapping("/{id}/update")
    public ApiResponse<AdminAnnouncementResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnnouncementRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.update(id, request, adminId));
    }

    /** 发布（body 可带 publishAt 定时；缺省立即生效） */
    @PostMapping("/{id}/publish")
    public ApiResponse<AdminAnnouncementResponse> publish(
            @PathVariable Long id,
            @RequestBody(required = false) PublishAnnouncementRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.publish(id, request, adminId));
    }

    /** 下线（PUBLISHED → OFFLINE；需重新 publish 才能恢复发布） */
    @PostMapping("/{id}/offline")
    public ApiResponse<AdminAnnouncementResponse> offline(@PathVariable Long id) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.offline(id, adminId));
    }

    /** 软删除（任意状态；已读回执保留） */
    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long adminId = UserContext.requireAdmin();
        announcementService.delete(id, adminId);
        return ApiResponse.ok(null);
    }

    /** 阅读统计（阅读人数 + 阅读率） */
    @GetMapping("/{id}/stats")
    public ApiResponse<AnnouncementStatsResponse> stats(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(announcementService.stats(id));
    }
}
