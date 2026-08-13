package org.quwuting.quwutingservice.points.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.points.dto.CheckInResponse;
import org.quwuting.quwutingservice.points.dto.GifterResponse;
import org.quwuting.quwutingservice.points.dto.GiftRequest;
import org.quwuting.quwutingservice.points.dto.GiftResponse;
import org.quwuting.quwutingservice.points.dto.PointsSummaryResponse;
import org.quwuting.quwutingservice.points.dto.PointsTransactionResponse;
import org.quwuting.quwutingservice.points.dto.RewardHintResponse;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 积分用户侧接口（除 /points/reward-hint 外均需登录——积分是用户资产，匿名无账户；
 * reward-hint 为公开只读的激励规则提示，匿名可调，见方法注释）。
 * <p>
 * 合规边界（V2）：积分仅能免费获得（打卡/上报采纳），无充值入口；
 * 赠送定位为"表达支持"，无现金/提现/兑换语义。
 */
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;

    /** 每日打卡（幂等：今日已打卡返回 checkedIn=false，不重复发分） */
    @PostMapping("/check-in")
    public ApiResponse<CheckInResponse> checkIn() {
        return ApiResponse.ok(pointsService.checkIn(UserContext.requireAuth()));
    }

    /** 积分页概览（余额 + 今日挣/赠 + 打卡态 + 规则文案） */
    @GetMapping("/me")
    public ApiResponse<PointsSummaryResponse> summary() {
        return ApiResponse.ok(pointsService.summary(UserContext.requireAuth()));
    }

    /**
     * 上报采纳奖励提示（2026-08-12 新增，<b>公开只读</b>——详情页「信息缺失？上报得积分」
     * 入口徽标 / 反馈面板激励条 / 提交成功提示消费）。全局配置无隐私，匿名可调：
     * 激励对匿名同样有意义（"登录后上报，被采纳可获 N 积分"登录引导）。与其他
     * /points 接口不同，<b>不 requireAuth</b>（类注释"均需登录"的例外，已注明）。
     */
    @GetMapping("/reward-hint")
    public ApiResponse<RewardHintResponse> rewardHint() {
        return ApiResponse.ok(pointsService.rewardHint());
    }

    /** 流水分页（type=ALL/EARN/GIFT，缺省 ALL） */
    @GetMapping("/transactions")
    public ApiResponse<Page<PointsTransactionResponse>> transactions(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(pointsService.listTransactions(UserContext.requireAuth(), type, page, size));
    }

    /** 赠送礼物（门店/舞伴双目标，2026-08-12 礼物化：载荷 = giftCode，价格后端权威校验） */
    @PostMapping("/gift")
    public ApiResponse<GiftResponse> gift(@Valid @RequestBody GiftRequest request) {
        return ApiResponse.ok(pointsService.gift(
                UserContext.requireAuth(), request.targetType(), request.targetId(), request.giftCode()));
    }

    /**
     * 某礼物的赠送者列表（礼物墙点击弹层，2026-08-12——公开只读，与详情页礼物墙同可见性）。
     * GET /points/gifters?targetType=VENUE&targetId=1&giftCode=ROSE
     */
    @GetMapping("/gifters")
    public ApiResponse<List<GifterResponse>> gifters(
            @RequestParam PointsTargetType targetType,
            @RequestParam Long targetId,
            @RequestParam String giftCode) {
        return ApiResponse.ok(pointsService.gifters(targetType, targetId, giftCode));
    }
}
