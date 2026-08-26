package org.quwuting.quwutingservice.points.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.points.dto.AdminDemandDetail;
import org.quwuting.quwutingservice.points.dto.AdminDemandItem;
import org.quwuting.quwutingservice.points.service.DemandRelayService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** 拒绝（POST /admin/demands/{id}/reject；舞伴回「不给」后一键操作） */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id) {
        UserContext.requireAdmin();
        demandRelayService.reject(id);
        return ApiResponse.ok(null);
    }
}
