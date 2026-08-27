package org.quwuting.quwutingservice.points.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.points.dto.CheckInResponse;
import org.quwuting.quwutingservice.points.dto.ContributionResponse;
import org.quwuting.quwutingservice.points.dto.DemandDetailResponse;
import org.quwuting.quwutingservice.points.dto.DemandRecordResponse;
import org.quwuting.quwutingservice.points.dto.FulfillmentResponse;
import org.quwuting.quwutingservice.points.dto.GifterResponse;
import org.quwuting.quwutingservice.points.dto.GiftRequest;
import org.quwuting.quwutingservice.points.dto.GiftResponse;
import org.quwuting.quwutingservice.points.dto.PointsSummaryResponse;
import org.quwuting.quwutingservice.points.dto.PointsTransactionResponse;
import org.quwuting.quwutingservice.points.dto.RewardHintResponse;
import org.quwuting.quwutingservice.points.dto.UnlockRequest;
import org.quwuting.quwutingservice.points.dto.UnlockResponse;
import org.quwuting.quwutingservice.points.dto.UpsertGateRequest;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.points.service.DemandFulfillmentService;
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
    private final ContributionService contributionService;
    private final DemandFulfillmentService demandFulfillmentService;

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

    /**
     * 设置/更新/清除积分门槛（2026-08-14 公共模块，需登录 + 目标属主/管理员）。
     * body {@code {targetType, targetId, cost}}：cost&gt;0 设置门槛（≤ 配置上限）；
     * cost=0 清除门槛（免费查看）。幂等 upsert。
     */
    @PostMapping("/gates")
    public ApiResponse<Void> upsertGate(@Valid @RequestBody UpsertGateRequest request) {
        Long userId = UserContext.requireAuth();
        pointsService.upsertGate(userId, request.targetType(), request.targetId(), request.cost(),
                UserContext.getCurrentRole());
        return ApiResponse.ok(null);
    }

    /**
     * 积分解锁内容（2026-08-14 公共模块，需登录）：消耗积分换取查看权（单向燃烧，
     * 不进任何接收方账户——合规红线见 AGENTS.md「积分系统 · 积分解锁」）。
     * 幂等：已解锁直接返回内容，不重复扣费。响应含解锁后余额与解锁内容。
     * <p>
     * 2026-08-24 联系方式扩展：targetType=DANCER_CONTACT 时——
     * <ul>
     *   <li>无门槛舞伴恒免费（放开门槛限制，需求弹层收集需求后仍走本接口）；</li>
     *   <li>有门槛舞伴每日首次获取免费（freeToday 响应字段，前端结果卡展示）；</li>
     *   <li>body.demand（服务≤2 + 时间≤2 + 时长可选）→ 服务端生成添加好友需求描述
     *       （响应 demandMessage）+ 需求记录落库（风控留痕）。</li>
     * </ul>
     */
    @PostMapping("/unlock")
    public ApiResponse<UnlockResponse> unlock(@Valid @RequestBody UnlockRequest request) {
        return ApiResponse.ok(pointsService.unlock(
                UserContext.requireAuth(), request.targetType(), request.targetId(), request.demand()));
    }

    /**
     * 我的邀约（2026-08-26，需登录）：个人中心「我的邀约」列表数据源。
     * 按当前用户过滤（只返回本人记录），分页倒序；行 = 舞伴摘要（软删/非 NORMAL 时
     * dancerVisible=false 前端禁跳）+ 需求描述原文 + 创建时间。
     */
    @GetMapping("/demands/mine")
    public ApiResponse<Page<DemandRecordResponse>> myDemands(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(pointsService.listMyDemands(UserContext.requireAuth(), page, size));
    }

    /**
     * 我的单条邀约详情（2026-08-26，需登录）：邀约详情页数据源——点击邀约进入
     * 详情（需求四要素表格 + 验证消息 + 舞伴摘要），而非舞伴主页。userId + id 双重
     * 归属校验（越权/不存在 → 1001「邀约不存在」）。
     */
    @GetMapping("/demands/{id}")
    public ApiResponse<DemandDetailResponse> demandDetail(@PathVariable Long id) {
        return ApiResponse.ok(pointsService.getMyDemand(UserContext.requireAuth(), id));
    }

    /**
     * 社区贡献档案（2026-08-27，需登录；docs/agents/23）：用户自己看自己的贡献
     * 聚合记录——贡献值 + 等级称号 + 各维度计数（上报采纳/打卡/认可/认领/分享/
     * 收藏）。只记录、不消耗、不公开广播（合规边界见 ContributionService）。
     */
    @GetMapping("/contributions")
    public ApiResponse<ContributionResponse> contributions() {
        return ApiResponse.ok(contributionService.summary(UserContext.requireAuth()));
    }

    /**
     * 确认履约（2026-08-27，需登录 + 本人；docs/agents/23「P1 履约闭环」）：
     * 客人确认本次邀约已履约完成 → 累计「与舞伴已合作 N 次」（私域信号，仅本人
     * 邀约详情 + 管理端邀约单可见）。幂等：已确认返回 confirmed=false + 既有数据；
     * 仅已获批发放联系方式的邀约可确认（未获批 → 1001）。
     */
    @PostMapping("/demands/{id}/confirm")
    public ApiResponse<FulfillmentResponse> confirmFulfillment(@PathVariable Long id) {
        return ApiResponse.ok(demandFulfillmentService.confirm(UserContext.requireAuth(), id));
    }
}
