package org.quwuting.quwutingservice.dancerule.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotSaveRequest;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotResponse;
import org.quwuting.quwutingservice.dancerule.service.RuleSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计价规则快照接口（2026-09-13，quwuting 仓 docs/agents/43-dance-timer.md §48）。
 * <p>
 * 全部接口需登录（userId 恒取登录态，禁客户端传入——隐私边界 = 配置用户自己
 * 可见）。读 = GET 一次拉回（清缓存/换机/跨设备恢复）；写 = POST 幂等整体覆盖
 * （本地变更后推送，last-write-wins by 服务端 updated_at 水位，冲突裁决在客户端）。
 */
@RestController
@RequestMapping("/dance-rules")
@RequiredArgsConstructor
public class RuleSnapshotController {

    private final RuleSnapshotService ruleSnapshotService;

    /**
     * 拉取规则快照（清缓存/换机恢复数据源）
     * GET /dance-rules/snapshot
     * <p>
     * 云端无快照返回 snapshot=null（合法状态 = 从未上过云），客户端走本地出厂 seed。
     */
    @GetMapping("/snapshot")
    public ApiResponse<RuleSnapshotResponse> get() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(ruleSnapshotService.get(userId));
    }

    /**
     * 幂等写入规则快照（本地规则配置 → 云端副本）
     * POST /dance-rules/snapshot（body: { snapshot }）——全仓 HTTP 语义只允许
     * GET/POST（见 AGENTS.md 最小事实），幂等性由快照整体覆盖语义保证（重复
     * POST 重放安全，同 PUT 语义）。
     * <p>
     * 响应.updatedAt 是新的同步水位（客户端据此推进本地 meta）。
     */
    @PostMapping("/snapshot")
    public ApiResponse<RuleSnapshotResponse> put(@RequestBody RuleSnapshotSaveRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(ruleSnapshotService.put(userId, request));
    }
}
