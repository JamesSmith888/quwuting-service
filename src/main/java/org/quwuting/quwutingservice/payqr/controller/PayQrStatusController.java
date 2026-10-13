package org.quwuting.quwutingservice.payqr.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.payqr.dto.PayQrDtos;
import org.quwuting.quwutingservice.payqr.service.PayQrStatusService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收款码配置状态接口（2026-10-13，V49；前端 = quwuting 仓 services/payQrStatus）。
 * <p>
 * 用户侧：POST 上报当前张数（幂等整体覆盖；userId 恒取登录态，禁客户端传入）。
 * admin 侧：汇总 + 名单（仅 ADMIN），入口在看板「计时 · 账本使用」面板。
 * 全仓 HTTP 语义只允许 GET/POST（AGENTS.md 最小事实），幂等性由整体覆盖语义保证。
 */
@RestController
@RequiredArgsConstructor
public class PayQrStatusController {

    private final PayQrStatusService payQrStatusService;

    /**
     * 上报本机收款码张数（需登录）。
     * POST /pay-qr/status　body: { count: 0~6 }——只有张数，协议上不存在图片 / 路径 / 摘要字段。
     */
    @PostMapping("/pay-qr/status")
    public ApiResponse<PayQrDtos.ReportResponse> report(@RequestBody PayQrDtos.ReportRequest request) {
        // requireAuth 放方法首位（重放安全不变量：鉴权先于任何副作用）
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(payQrStatusService.report(userId, request.count()));
    }

    /** 汇总（仅 ADMIN）：GET /admin/pay-qr/stats */
    @GetMapping("/admin/pay-qr/stats")
    public ApiResponse<PayQrDtos.AdminStats> adminStats() {
        UserContext.requireAdmin();
        return ApiResponse.ok(payQrStatusService.adminStats());
    }

    /** 名单（仅 ADMIN）：GET /admin/pay-qr/users——配置过收款码的真实用户，最近变更在前 */
    @GetMapping("/admin/pay-qr/users")
    public ApiResponse<PayQrDtos.AdminUsers> adminUsers() {
        UserContext.requireAdmin();
        return ApiResponse.ok(payQrStatusService.adminUsers());
    }
}
