package org.quwuting.quwutingservice.spend.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.spend.dto.SpendEntriesResponse;
import org.quwuting.quwutingservice.spend.dto.SpendOverviewResponse;
import org.quwuting.quwutingservice.spend.dto.SpendSyncRequest;
import org.quwuting.quwutingservice.spend.dto.SpendSyncResponse;
import org.quwuting.quwutingservice.spend.service.SpendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费账本接口（V1 上云版，2026-09-09，docs/agents/44-spend-ledger.md）。
 * <p>
 * 全部接口需登录（userId 恒取登录态，禁客户端传入——隐私边界 = 数据用户自己
 * 可见，无任何公开分发口径）。写路径仅 sync 一个批量幂等入口（本地为源、云端
 * 为镜）；读路径 overview 一次聚合 + 游标增量拉取。
 */
@RestController
@RequestMapping("/spend")
@RequiredArgsConstructor
public class SpendController {

    private final SpendService spendService;

    /**
     * 批量幂等同步（本地账目 → 云端副本）
     * POST /spend/entries/sync
     * <p>
     * body.entries ≤ 200 条；同 clientEntryId 重放幂等收敛；删除经 deleted=true
     * 承载（软删）。部分成功不整体回滚（rejected 计数返回，客户端重放无副作用）。
     */
    @PostMapping("/entries/sync")
    public ApiResponse<SpendSyncResponse> sync(@RequestBody SpendSyncRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(spendService.sync(userId, request));
    }

    /**
     * 月度总览（统计页数据源，一次往返返回全部）
     * GET /spend/overview?month=yyyy-MM（缺省当前月）
     */
    @GetMapping("/overview")
    public ApiResponse<SpendOverviewResponse> overview(
            @RequestParam(required = false) String month) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(spendService.overview(userId, month));
    }

    /**
     * 增量拉取（跨设备/换机恢复；含软删行，客户端据 deleted 删本地副本）
     * GET /spend/entries?cursor=<epoch 毫秒>（缺省 0 = 全量首拉）
     */
    @GetMapping("/entries")
    public ApiResponse<SpendEntriesResponse> entries(
            @RequestParam(required = false) Long cursor) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(spendService.entries(userId, cursor));
    }
}
