package org.quwuting.quwutingservice.venueactivity.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.venueactivity.dto.response.VenueActivityBadgeResponse;
import org.quwuting.quwutingservice.venueactivity.dto.response.VenueActivityResponse;
import org.quwuting.quwutingservice.venueactivity.service.VenueActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 门店营业活动 · 用户端接口（只读 + 打卡）。
 * <p>
 * 全部 GET/POST（项目 HTTP 铁律），无 PUT/PATCH/DELETE。
 * <p>
 * 可见性：{@code /venues/{venueId}/activities} 与门店详情页同权限——**未登录
 * 即可读**。这是"用户到店后进小程序"链路的关键一环：扫码后落地该店详情页时
 * 一步授权都不能要（登录成本必须推迟到用户已经拿到价值之后），所以活动与口令
 * 必须零门槛可见，打卡才需要登录。
 */
@RestController
@RequiredArgsConstructor
public class VenueActivityController {

    private final VenueActivityService venueActivityService;

    /** 门店当前可见活动（详情页活动卡数据源） */
    @GetMapping("/venues/{venueId}/activities")
    public ApiResponse<List<VenueActivityResponse>> listByVenue(@PathVariable Long venueId) {
        return ApiResponse.ok(venueActivityService.listForVenue(venueId));
    }

    /**
     * 门店列表页活动标记批量查询：返回 venueId → 标记**列表**（含当前态与下一次变化时刻）。
     * <p>
     * <b>下发条件 = 活动在当前日期范围内</b>（不是"此刻正好命中时段"）——
     * 与门店营业状态徽标同源：「营业中 / 未到营业时间 → X HH:mm 开门」，
     * 状态要在、语气降级。未到时段时前端渲染时间（"13:00 起"），
     * 命中时段时渲染权益短标签（"买一送一"）。
     * <p>
     * <b>为什么是数组（2026-09-20 改）</b>：列表页那一行已支持多条轮播（同「最新上报」
     * 信号行的 4s 节奏），只下发一条会让第 2 条以后的活动在列表上没有任何出口。
     * 顺序由服务端按 {@code compareBadgePriority} 排好（索引 0 最先展示），
     * 客户端只消费顺序、不重排——运营调整优先级的能力不能落到客户端。
     * <p>
     * **已彻底过期的活动不下发**（那不是降级，是这条活动已不存在）；
     * 无活动的门店不返回键，前端用"有没有这个键"直接决定渲染。
     */
    @GetMapping("/venues/activity-badges")
    public ApiResponse<Map<Long, List<VenueActivityBadgeResponse>>> activityBadges(
            @RequestParam("venueIds") Collection<Long> venueIds) {
        return ApiResponse.ok(venueActivityService.badgeByVenueIds(venueIds));
    }

    /**
     * 到店打卡（幂等，非累计动作）。activityDate 由服务端取今天，客户端不传。
     */
    @PostMapping("/venues/{venueId}/activities/{activityId}/checkin")
    public ApiResponse<VenueActivityResponse> checkin(@PathVariable Long venueId,
                                                      @PathVariable Long activityId) {
        return ApiResponse.ok(venueActivityService.checkin(venueId, activityId));
    }
}
