package org.quwuting.quwutingservice.bulletin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.bulletin.dto.request.AgentPublishBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.CreateBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.PublishBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.UpdateBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.response.AdminBulletinResponse;
import org.quwuting.quwutingservice.bulletin.service.BulletinService;
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
 * 行业快讯管理端接口（2026-09-10，docs/agents/47-bulletins.md，需 ADMIN）。
 * <p>
 * 管理面在 Web 管理后台（quwuting-admin-web「快讯管理」入口）；遵守项目 HTTP
 * 约定仅 GET/POST（写操作一律 POST action 风格，禁 PUT/PATCH/DELETE）。
 * 状态机：DRAFT → PUBLISHED（立即/定时）→ OFFLINE；任意态软删除。
 * <p>
 * <b>Agent 通道</b>：{@code POST /agent-publish} 面向 Agent skill 的一步发布接口
 * （create + publish 合并 + dedupKey 幂等），source 由服务端固定 AGENT。
 * 内容来源是人工收集后经此接口投放，<b>小程序端不存在任何投稿入口</b>。
 */
@RestController
@RequestMapping("/admin/bulletins")
@RequiredArgsConstructor
public class AdminBulletinController {

    private final BulletinService bulletinService;

    /** 快讯列表（状态/来源筛选 + 分页，id 倒序；恒只含 FLASH 分类） */
    @GetMapping
    public ApiResponse<Page<AdminBulletinResponse>> list(
            @RequestParam(required = false) AnnouncementStatus status,
            @RequestParam(required = false) AnnouncementSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.adminList(status, source, page, size));
    }

    /** 快讯详情 / 编辑回显 */
    @GetMapping("/{id}")
    public ApiResponse<AdminBulletinResponse> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.adminDetail(id));
    }

    /** 创建快讯（存草稿；source 固定 MANUAL、category 固定 FLASH） */
    @PostMapping("/create")
    public ApiResponse<AdminBulletinResponse> create(
            @Valid @RequestBody CreateBulletinRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.create(request, adminId));
    }

    /** 更新快讯（DRAFT 全字段；PUBLISHED 除 publishAt 外可改；OFFLINE 禁改） */
    @PostMapping("/{id}/update")
    public ApiResponse<AdminBulletinResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBulletinRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.update(id, request, adminId));
    }

    /** 发布（body 可带 publishAt 定时；缺省立即生效） */
    @PostMapping("/{id}/publish")
    public ApiResponse<AdminBulletinResponse> publish(
            @PathVariable Long id,
            @RequestBody(required = false) PublishBulletinRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.publish(id, request, adminId));
    }

    /** 下线（PUBLISHED → OFFLINE；需重新 publish 才能恢复发布） */
    @PostMapping("/{id}/offline")
    public ApiResponse<AdminBulletinResponse> offline(@PathVariable Long id) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.offline(id, adminId));
    }

    /** 软删除（任意状态） */
    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long adminId = UserContext.requireAdmin();
        bulletinService.delete(id, adminId);
        return ApiResponse.ok(null);
    }

    /**
     * Agent 一步发布（create + publish 合并；dedupKey 幂等；source 固定 AGENT）。
     * <p>
     * 供 Agent skill 批量投放行业情报使用（docs/agents/47「Agent 接口契约」）：
     * 同 dedupKey 重跑返回已存在条目而非重复投放，保证采集任务可安全重试。
     */
    @PostMapping("/agent-publish")
    public ApiResponse<AdminBulletinResponse> agentPublish(
            @Valid @RequestBody AgentPublishBulletinRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(bulletinService.agentPublish(request, adminId));
    }
}
