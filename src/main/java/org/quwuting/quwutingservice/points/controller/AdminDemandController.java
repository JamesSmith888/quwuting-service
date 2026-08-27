package org.quwuting.quwutingservice.points.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.enums.DemandRejectReason;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.dto.AdminDemandDetail;
import org.quwuting.quwutingservice.points.dto.AdminDemandItem;
import org.quwuting.quwutingservice.points.dto.RejectDemandRequest;
import org.quwuting.quwutingservice.points.dto.RescueDemandRequest;
import org.quwuting.quwutingservice.points.service.DemandRelayService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 邀约工作台管理端接口（2026-08-26，22-invite-relay-and-auto-release；仅 ADMIN）。
 * <p>
 * 定位：平台管理员微信人工中转的支撑——待办列表（含一键复制转发话术的素材）+
 * 邀约单详情（完整邀约单 + 保存图片/分享/复制的三出口素材）+ 发放/拒绝（按舞伴
 * 微信回复「给/不给」一键操作）+ 待办计数（me 页入口红点）。无用户间通信
 * （合规核心：客人只与平台交互，舞伴通过管理员微信人工联系）。
 */
@RestController
@RequestMapping("/admin/demands")
@RequiredArgsConstructor
public class AdminDemandController {

    private final DemandRelayService demandRelayService;

    /** 待办列表（GET /admin/demands/pending，分页倒序；行含舞伴/客人摘要 +
     *  message 原文 + 超 12h 催办标记） */
    @GetMapping("/pending")
    public ApiResponse<Page<AdminDemandItem>> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        return ApiResponse.ok(demandRelayService.listPending(page, size));
    }

    /** 邀约工作台列表（GET /admin/demands?scope=pending|processed|all，分页倒序；
     *  单一列表端点覆盖 待处理/已处理/全部 三视图——scope 为查询正交维度，与状态机
     *  解耦；非法/缺省 scope 回退 pending）。行含 status（列表自描述，已处理视图直接
     *  渲染状态）。待办红点仍走 /pending-count，不受影响。 */
    @GetMapping
    public ApiResponse<Page<AdminDemandItem>> list(
            @RequestParam(defaultValue = "pending") String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext.requireAdmin();
        if (!List.of("pending", "processed", "all").contains(scope)) {
            scope = "pending";
        }
        return ApiResponse.ok(demandRelayService.listByScope(scope, page, size));
    }

    /** 待办总数（GET /admin/demands/pending-count；me 页「邀约工作台」入口红点
     *  数据源——与 GET /admin/reports/pending-count 同模式，红点只提示"有待办"，
     *  计数随发放/拒绝动作自然归零，无独立已读态） */
    @GetMapping("/pending-count")
    public ApiResponse<Long> pendingCount() {
        UserContext.requireAdmin();
        return ApiResponse.ok(demandRelayService.countPending());
    }

    /** 邀约单详情（GET /admin/demands/{id}；工作台行点击 → 完整邀约单——客人
     *  公开资料 + 舞伴摘要 + 需求四要素结构化字段 + demandDetailText 多行文本
     *  （保存图片/分享/复制的三出口素材）+ status（非 PENDING 前端禁用发放/拒绝）） */
    @GetMapping("/{id}")
    public ApiResponse<AdminDemandDetail> detail(@PathVariable Long id) {
        UserContext.requireAdmin();
        return ApiResponse.ok(demandRelayService.getDetail(id));
    }

    /** 发放联系方式（POST /admin/demands/{id}/approve；舞伴回「给」后一键操作） */
    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable Long id) {
        UserContext.requireAdmin();
        demandRelayService.approve(id);
        return ApiResponse.ok(null);
    }

    /**
     * 拒绝（POST /admin/demands/{id}/reject；舞伴回「不给」后一键操作）。
     * 2026-08-27（V55，docs/agents/24）：body 选填拒绝原因（DemandRejectReason
     * code，可空 = 旧客户端/未选——客人侧回退通用状态文案，拒绝动作不因原因
     * 字段失败；非法 code 应用层 parseOrNull 按 null 防御）。
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                    @RequestBody(required = false) RejectDemandRequest request) {
        UserContext.requireAdmin();
        DemandRejectReason reason = request == null ? null
                : DemandRejectReason.parseOrNull(request.reason());
        demandRelayService.reject(id, reason);
        return ApiResponse.ok(null);
    }

    /**
     * 代找替代舞伴（POST /admin/demands/{id}/rescue；仅 ADMIN，2026-08-27，
     * V55，docs/agents/24「换乘站」）：被拒/超时邀约 → 管理员微信人工确认替代
     * 舞伴同意 → 平台以原邀约四要素 + message 原样代建 APPROVED 替代邀约（直接
     * 发放替代舞伴联系方式 + 站内信通知客人直达新邀约详情）。幂等：一次救援
     * 只产出一条替代邀约（origin_demand_id 部分唯一索引兜底），重复 → 1001。
     */
    @PostMapping("/{id}/rescue")
    public ApiResponse<Long> rescue(@PathVariable Long id,
                                    @RequestBody(required = false) RescueDemandRequest request) {
        UserContext.requireAdmin();
        if (request == null || request.targetDancerId() == null) {
            throw new BusinessException(1001, "请选择替代舞伴");
        }
        return ApiResponse.ok(demandRelayService.rescue(id, request.targetDancerId()));
    }
}
