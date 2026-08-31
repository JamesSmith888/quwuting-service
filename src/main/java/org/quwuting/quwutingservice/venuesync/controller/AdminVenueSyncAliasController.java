package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuesync.dto.request.UpsertVenueSyncAliasRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueSyncAliasResponse;
import org.quwuting.quwutingservice.venuesync.service.VenueSyncAliasService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 手动映射别名管理端接口（2026-08-31，仅平台管理员）。
 * <p>
 * 调用方 = Web 管理后台（quwuting-admin-web）「门店同步 → 映射管理」；
 * 消费方 = 管线（quwuting-ops/venue-opening --refresh-aliases）。
 * <ul>
 *   <li>GET    /admin/venue-sync/aliases          — 全部有效映射（带平台门店名）</li>
 *   <li>POST   /admin/venue-sync/aliases          — 幂等 upsert（同城同名覆盖）</li>
 *   <li>DELETE /admin/venue-sync/aliases/{id}     — 软删映射</li>
 *   <li>GET    /admin/venue-sync/aliases/export   — 管线消费格式 {city: {sourceName: venueName}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venue-sync/aliases")
@RequiredArgsConstructor
public class AdminVenueSyncAliasController {

    private final VenueSyncAliasService aliasService;

    /** 全部有效映射（带平台门店名，最近配置在前） */
    @GetMapping
    public ApiResponse<List<VenueSyncAliasResponse>> list() {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.list());
    }

    /** 幂等 upsert（同城同名覆盖 venueId/note） */
    @PostMapping
    public ApiResponse<VenueSyncAliasResponse> upsert(
            @Valid @RequestBody UpsertVenueSyncAliasRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.upsert(request));
    }

    /** 软删映射 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        UserContext.requireAdmin();
        aliasService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 管线消费格式：{city: {sourceName: venueName}}（对齐 matcher aliases.json） */
    @GetMapping("/export")
    public ApiResponse<Map<String, Map<String, String>>> export() {
        UserContext.requireAdmin();
        return ApiResponse.ok(aliasService.export());
    }
}
