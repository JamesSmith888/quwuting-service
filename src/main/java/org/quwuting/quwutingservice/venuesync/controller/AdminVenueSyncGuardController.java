package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuesync.dto.request.VenueGuardExemptRequest;
import org.quwuting.quwutingservice.venuesync.dto.request.VenueGuardIdsRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueGuardStateItem;
import org.quwuting.quwutingservice.venuesync.service.VenueGuardAdminService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店状态权威层级 · 管理端接口（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 背景：门店状态存在两个信息来源——管理员人工判断 vs 每日舞讯（第三方整理，会漏报/误报）。
 * 规则 = 人工 &gt; 外部推断，但人工判断也会过期，故人工改状态时打一个有时限的「人工锁」；
 * 锁内每日舞讯批量写库会逐店跳过（不写库、不通知、不发公告）。
 * <p>
 * 本组接口是这套机制的**人工侧操作面**（全部 requireAdmin）：
 * <ul>
 *   <li>POST /admin/venue-sync/guard/query  — 读权威层级状态（编辑页展示 + 列表徽标）；</li>
 *   <li>POST /admin/venue-sync/guard/unlock — 恢复自动同步（提前释放人工锁）；</li>
 *   <li>POST /admin/venue-sync/guard/exempt — 设置/撤销「不参与舞讯推断」的永久豁免。</li>
 * </ul>
 * 三者都不改 status 本身：改状态仍走既有通道（{@code POST /venues/{id}/update} 人工通道
 * 会自动打锁；{@code /admin/venue-daily-openings/**} 外部通道受锁约束）。
 * <p>
 * 用 POST 承载批量 ID（项目禁 PUT/PATCH/DELETE）。
 */
@RestController
@RequestMapping("/admin/venue-sync/guard")
@RequiredArgsConstructor
public class AdminVenueSyncGuardController {

    private final VenueGuardAdminService venueGuardAdminService;

    /** 读权威层级状态（编辑页 / 列表徽标；单店查询传长度 1 的列表即可） */
    @PostMapping("/query")
    public ApiResponse<List<VenueGuardStateItem>> query(
            @Valid @RequestBody VenueGuardIdsRequest request) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venueGuardAdminService.query(request.venueIds()));
    }

    /** 恢复自动同步：清除人工锁（幂等，返回实际解锁家数） */
    @PostMapping("/unlock")
    public ApiResponse<Integer> unlock(@Valid @RequestBody VenueGuardIdsRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(venueGuardAdminService.unlock(request.venueIds(), adminId));
    }

    /** 设置 / 撤销「舞讯推断永久豁免」（幂等，返回实际变更家数） */
    @PostMapping("/exempt")
    public ApiResponse<Integer> exempt(@Valid @RequestBody VenueGuardExemptRequest request) {
        Long adminId = UserContext.requireAdmin();
        return ApiResponse.ok(venueGuardAdminService.setExempt(
                request.venueIds(), request.exempt(), request.note(), adminId));
    }
}
