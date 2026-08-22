package org.quwuting.quwutingservice.venue.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.service.AmapVenuePhotoSyncService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店图片同步管理端接口（仅 ADMIN，2026-08-21 新增，2026-08-22 扩展工作台）。
 * <p>
 * 背景：存量门店缺主图（image_url 为空），一键调用高德地图 Web 服务 place/text
 * 关键词搜索（extensions=all）取官方图床 URL 直接写入 image_url——不下载图片到
 * Supabase（省存储 + 高德 CDN 直出，用户决策）。key 只放后端配置（app.amap.key），
 * 不落前端。
 * <p>
 * <b>2026-08-22 扩展「图片同步工作台 + 纠错生命周期」</b>（成功 ≠ 100% 正确）：
 * <ul>
 *   <li>GET /admin/venues/photo-sync/missing-count — 缺图待同步数量</li>
 *   <li>POST /admin/venues/photo-sync/run — 异步触发全量同步（返回是否已启动）</li>
 *   <li>GET /admin/venues/photo-sync/progress — 实时进度轮询（统计 + 逐条结果）</li>
 *   <li>GET /admin/venues/photo-sync/list — 门店图片状态分页（DB 现状：主图有无/城市/
 *       名称筛选，服务重启不丢；兼作成功项纠错入口）</li>
 *   <li>POST /admin/venues/photo-sync/retry — 单店重匹配（address 可选：空 = 名称模式
 *       强制重匹配；非空 = 地址模式，跳过名称校验取第一个 POI）</li>
 *   <li>POST /admin/venues/photo-sync/clear — 清除门店图片（image_url 置空 + 删高德
 *       导入相册，回到无图态可重新同步）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/venues/photo-sync")
@RequiredArgsConstructor
public class AdminVenuePhotoSyncController {

    private final AmapVenuePhotoSyncService photoSyncService;

    /** 单店重匹配请求体（address 可选：空 = 名称模式；非空 = 地址模式） */
    public record RetryRequest(@Min(1) long venueId, String address) {
    }

    /** 清除门店图片请求体 */
    public record ClearRequest(@Min(1) long venueId) {
    }

    /** 启动结果（started=false = 已有同步进行中，防并发重复触发） */
    public record StartResult(boolean started) {
    }

    /** 缺图待同步数量（需 ADMIN）。 */
    @GetMapping("/missing-count")
    public ApiResponse<Long> missingCount() {
        UserContext.requireAdmin();
        return ApiResponse.ok(photoSyncService.countMissing());
    }

    /** 异步触发全量同步（需 ADMIN；已有同步进行中时返回 started=false）。 */
    @PostMapping("/run")
    public ApiResponse<StartResult> run() {
        UserContext.requireAdmin();
        return ApiResponse.ok(new StartResult(photoSyncService.startSync()));
    }

    /** 实时进度轮询（需 ADMIN；无任务时返回最近一次结果快照）。 */
    @GetMapping("/progress")
    public ApiResponse<AmapVenuePhotoSyncService.SyncProgress> progress() {
        UserContext.requireAdmin();
        return ApiResponse.ok(photoSyncService.getProgress());
    }

    /**
     * 门店图片状态分页（需 ADMIN；数据源 = DB 现状）。
     * hasImage：true = 有主图 / false = 无主图 / 不传 = 全部。
     */
    @GetMapping("/list")
    public ApiResponse<Page<AmapVenuePhotoSyncService.VenuePhotoStatusItem>> list(
            @RequestParam(required = false) Boolean hasImage,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(photoSyncService.listPhotoStatus(hasImage, city, keyword, page, size));
    }

    /**
     * 单店重匹配（需 ADMIN；同步返回单条结果，前端就地更新）。
     * address 可选：空 = 按门店名名称匹配强制重取；非空 = 按完整地址检索取第一个 POI。
     */
    @PostMapping("/retry")
    public ApiResponse<AmapVenuePhotoSyncService.SyncItem> retry(
            @Valid @RequestBody RetryRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(photoSyncService.retrySync(request.venueId(), request.address()));
    }

    /** 清除门店图片（需 ADMIN；人工判定错配后回退到无图态，可重新同步）。 */
    @PostMapping("/clear")
    public ApiResponse<Void> clear(@Valid @RequestBody ClearRequest request) {
        UserContext.requireAdmin();
        photoSyncService.clearPhotos(request.venueId());
        return ApiResponse.ok(null);
    }
}
