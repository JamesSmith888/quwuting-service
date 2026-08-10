package org.quwuting.quwutingservice.dancer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.request.AddDancerPhotosRequest;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerPhotoResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerSummaryResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTagStat;
import org.quwuting.quwutingservice.dancer.dto.response.RecognizeResponse;
import org.quwuting.quwutingservice.dancer.service.DancerService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 舞伴生态体系公开/用户接口（路由挂载在 /dancers 下）。
 * 接口集合与规范对应需求（见 AGENTS.md「舞伴生态体系」）：
 * <ul>
 *   <li>GET /dancers — 舞伴列表（公开，登录时含个人认可态；city 可选筛选）</li>
 *   <li>GET /dancers/cities — 常驻城市词表（列表页城市筛选；聚合真实数据）</li>
 *   <li>POST /dancers — 舞伴主动注册（登录，status=PENDING 待认证）</li>
 *   <li>GET /dancers/{id} — 舞伴详情（公开，可见性规则见 DancerService）</li>
 *   <li>PUT /dancers/{id} — 编辑本人/管理舞伴资料（全量覆盖；REJECTED → 自动重审）</li>
 *   <li>GET /dancers/{id}/tags — 舞伴标签聚合（公开）</li>
 *   <li>POST /dancers/{id}/recognitions — 认可 toggle（登录）</li>
 *   <li>POST /dancers/{id}/photos — 本人/管理员上传相册（插入即 PENDING 待审）</li>
 *   <li>DELETE /dancers/{id}/photos/{photoId} — 本人/管理员删除照片</li>
 * </ul>
 * 认可体系语义（产品定位）：用户认可/支持/点赞，不含打赏、礼物、虚拟币等金钱/排行概念。
 */
@RestController
@RequestMapping("/dancers")
@RequiredArgsConstructor
public class DancerController {

    private final DancerService dancerService;

    /**
     * 舞伴列表（公开，软鉴权：登录时返回个人"今日已认可"状态）。
     * 支持按常驻城市筛选（city 可选）；排序由后端完成（近7天认可倒序，见 DancerService#listPublic）。
     */
    @GetMapping
    public ApiResponse<Page<DancerSummaryResponse>> list(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(dancerService.listPublic(city, page, size, UserContext.getCurrentUserId()));
    }

    /**
     * 舞伴主动注册（需登录）→ status=PENDING（审核中），管理员认证后公开。
     * 返回新建舞伴 ID（前端据此跳转详情页）。
     */
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody UpsertDancerRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.createDancer(userId, request, false));
    }

    /**
     * 编辑舞伴资料（本人 createdBy 匹配 或 管理员）：全量覆盖可编辑字段；
     * REJECTED 资料编辑后自动回到 PENDING（重新送审）。返回更新后详情。
     */
    @PutMapping("/{id}")
    public ApiResponse<DancerDetailResponse> update(@PathVariable Long id,
                                                    @Valid @RequestBody UpsertDancerRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.updateDancer(userId, id, request, UserContext.getCurrentRole()));
    }

    /**
     * 舞伴详情（公开，软鉴权：登录时返回我的认可态与 isMine）。
     * PENDING/HIDDEN 资料仅创建人本人与管理员可见（服务层可见性校验）。
     */
    @GetMapping("/{id}")
    public ApiResponse<DancerDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.getDetail(id, UserContext.getCurrentUserId(), UserContext.getCurrentRole()));
    }

    /** 舞伴标签聚合（公开；标签来源 = 用户认可行为） */
    @GetMapping("/{id}/tags")
    public ApiResponse<List<DancerTagStat>> getTags(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.getTags(id, UserContext.getCurrentUserId(), UserContext.getCurrentRole()));
    }

    /**
     * 认可 toggle（需登录）：今日未认可 → 认可（body.tags 可选字典标签 0-3 个）；
     * 今日已认可 → 取消。返回最终参与态 + 最新四窗口统计（前端据此本地更新，无需整页刷新）。
     */
    @PostMapping("/{id}/recognitions")
    public ApiResponse<RecognizeResponse> recognize(@PathVariable Long id,
                                                    @Valid @RequestBody(required = false) RecognizeDancerRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.toggleRecognize(userId, id, request, UserContext.getCurrentRole()));
    }

    /**
     * 常驻城市词表（公开；列表页城市筛选数据源——聚合真实数据，新增城市自动出现，
     * 与 venue 域 /venues/cities 同模式）。
     */
    @GetMapping("/cities")
    public ApiResponse<List<String>> cities() {
        return ApiResponse.ok(dancerService.listPublicCities());
    }

    /**
     * 本人/管理员上传相册照片（需登录 + canManage）：插入即 PENDING（先审后发）。
     * 返回本人视角全量照片（含刚上传的待审项，编辑页据此刷新）。
     */
    @PostMapping("/{id}/photos")
    public ApiResponse<List<DancerPhotoResponse>> addPhotos(@PathVariable Long id,
                                                            @Valid @RequestBody AddDancerPhotosRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.addPhotos(userId, id, request.urls(), UserContext.getCurrentRole()));
    }

    /** 本人/管理员删除照片（软删；普通用户不可调用） */
    @DeleteMapping("/{id}/photos/{photoId}")
    public ApiResponse<Void> removePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        Long userId = UserContext.requireAuth();
        dancerService.removePhoto(userId, id, photoId, UserContext.getCurrentRole());
        return ApiResponse.ok(null);
    }
}
