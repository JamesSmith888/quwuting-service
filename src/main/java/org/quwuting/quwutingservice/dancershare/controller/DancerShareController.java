package org.quwuting.quwutingservice.dancershare.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancershare.dto.request.RecordShareOpenRequest;
import org.quwuting.quwutingservice.dancershare.dto.request.RecordShareRequest;
import org.quwuting.quwutingservice.dancershare.service.DancerShareService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 舞伴分享事件上报端点（分享追踪 P2 数据通道，2026-08-13 新增，镜像场所分享端点）。
 * <p>
 * 语义与 {@code POST /dancers/{id}/recognitions} 无关——分享是独立数据通道：
 * fire-and-forget、软鉴权（匿名可上报，身份仅作归因）、失败不影响主流程。
 * 分享维度不参与热度公式。
 */
@RestController
@RequestMapping("/dancers")
@RequiredArgsConstructor
public class DancerShareController {

    private final DancerShareService dancerShareService;

    /**
     * 记录一次分享动作（分享面板弹出时由 onShareAppMessage / onShareTimeline 触发）。
     * POST /dancers/{id}/shares  body: {"channel": "BUTTON"|"MENU"|"TIMELINE"}（可选）
     */
    @PostMapping("/{id}/shares")
    public ApiResponse<Void> recordShare(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RecordShareRequest request
    ) {
        String channel = request != null ? request.channel() : null;
        dancerShareService.recordShare(id, UserContext.getCurrentUserId(), channel);
        return ApiResponse.ok(null);
    }

    /**
     * 记录一次分享打开（被分享者打开舞伴详情页且路径携带 share_from 时触发）。
     * POST /dancers/{id}/share-opens  body: {"shareFrom": 123, "demandId": 456}（可选）
     * demandId（2026-08-27，V56，docs/agents/25「分享闭环自动化」）：邀约落地页
     * 打开时透传——服务端同步置该邀约 share_opened_at（幂等），客人侧
     * 「TA 已查看你的邀约」零操作自动感知。
     */
    @PostMapping("/{id}/share-opens")
    public ApiResponse<Void> recordShareOpen(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RecordShareOpenRequest request
    ) {
        Long shareFrom = request != null ? request.shareFrom() : null;
        Long demandId = request != null ? request.demandId() : null;
        dancerShareService.recordOpen(id, UserContext.getCurrentUserId(), shareFrom, demandId);
        return ApiResponse.ok(null);
    }
}
