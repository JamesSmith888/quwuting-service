package org.quwuting.quwutingservice.tagdict.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.tagdict.dto.request.CreateTagDictRequest;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;
import org.quwuting.quwutingservice.tagdict.service.TagDictService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签字典管理端接口（2026-08-24，仅平台管理员）。
 * POST /admin/tag-dict — 管理员新增标签（text + 可选 description + 可选 scope，
 * 入字典后立即可选；同 scope + text 已存在 → 1001「该标签已存在」）。
 * 低频管理操作：无缓存、无失效矩阵（编辑页每次进入拉取最新字典）。
 */
@RestController
@RequestMapping("/admin/tag-dict")
@RequiredArgsConstructor
public class AdminTagDictController {

    private final TagDictService tagDictService;

    /** 管理员新增标签（入字典立即可选；返回含新 id，前端回填本地字典并选中） */
    @PostMapping
    public ApiResponse<TagItemResponse> create(@Valid @RequestBody CreateTagDictRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(tagDictService.create(adminId, request));
    }
}
