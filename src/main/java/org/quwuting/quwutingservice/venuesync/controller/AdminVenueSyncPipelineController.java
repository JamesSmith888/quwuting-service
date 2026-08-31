package org.quwuting.quwutingservice.venuesync.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuesync.dto.request.PipelineRunRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.PipelineRunStatusResponse;
import org.quwuting.quwutingservice.venuesync.service.VenueSyncPipelineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店营业管线执行管理端接口（2026-08-31，仅平台管理员）。
 * <p>
 * 调用方 = Web 管理后台（quwuting-admin-web）「门店同步 → 拉取数据」；
 * 作用 = 在页面上触发管线跑一次「抓取 + 匹配 + 上报报告」，替代手工执行
 * {@code python3 main.py --upload-report}。
 * <ul>
 *   <li>POST /admin/venue-sync/pipeline/run    — 触发执行（异步，立即返回状态）</li>
 *   <li>GET  /admin/venue-sync/pipeline/status — 最近一次执行状态 + 日志尾部（页面轮询）</li>
 * </ul>
 * 安全：子进程以触发者管理端 token 为 ADMIN_TOKEN，复用 Web 登录态；非管理员 403。
 */
@RestController
@RequestMapping("/admin/venue-sync/pipeline")
@RequiredArgsConstructor
public class AdminVenueSyncPipelineController {

    private final VenueSyncPipelineService pipelineService;

    /** 触发一次管线执行（异步返回；正在运行时抛 5004） */
    @PostMapping("/run")
    public ApiResponse<PipelineRunStatusResponse> run(
            @Valid @RequestBody(required = false) PipelineRunRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserContext.requireAdmin();
        String source = request == null ? "xianbao360" : request.source();
        boolean refreshSnapshot = request != null && Boolean.TRUE.equals(request.refreshSnapshot());
        pipelineService.start(source, refreshSnapshot, extractToken(authorization));
        return ApiResponse.ok(pipelineService.status());
    }

    /** 最近一次执行状态（页面轮询；无历史返回 IDLE） */
    @GetMapping("/status")
    public ApiResponse<PipelineRunStatusResponse> status() {
        UserContext.requireAdmin();
        return ApiResponse.ok(pipelineService.status());
    }

    private String extractToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return authorization;
    }
}
