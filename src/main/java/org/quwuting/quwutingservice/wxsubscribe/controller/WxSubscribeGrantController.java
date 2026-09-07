package org.quwuting.quwutingservice.wxsubscribe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.wxsubscribe.dto.request.WxSubscribeGrantRequest;
import org.quwuting.quwutingservice.wxsubscribe.dto.response.WxSubscribeStatusResponse;
import org.quwuting.quwutingservice.wxsubscribe.service.WxSubscribeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信订阅消息授权/状态（2026-09-07 新增，均需登录）。
 * <p>
 * POST /user/wx-subscribe-grants  — 前端 wx.requestSubscribeMessage 返回 accept
 * 后 fire-and-forget 上报，服务端 upsert 累加一条发送额度（额度模型见
 * {@code WxSubscribeQuotaRepository} 注释）。reject/ban 不上报（无额度语义，
 * 零增量）；非配置模板静默忽略（防任意模板灌脏数据）。
 * GET  /user/wx-subscribe-status — 当前用户额度状态（三态渲染数据源：
 * 从未授权 / 有额度 / 已用完），门店更多菜单「微信提醒」子项与通知落地
 * 提示条共用。
 */
@Slf4j
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class WxSubscribeGrantController {

    private final WxSubscribeService wxSubscribeService;

    /** 订阅消息模板 ID（与发送侧同一配置源，服务端唯一认账的模板） */
    @Value("${wechat.subscribe.status-template-id}")
    private String statusTemplateId;

    /**
     * 记录一次订阅授权（POST /user/wx-subscribe-grants）。
     * 幂等语义：每次 accept = 一条额度（微信一次性订阅机制天然一一对应）。
     */
    @PostMapping("/wx-subscribe-grants")
    public ApiResponse<Void> grant(@Valid @RequestBody WxSubscribeGrantRequest request) {
        Long userId = UserContext.requireAuth();
        if (!statusTemplateId.equals(request.templateId())) {
            // 非配置模板：静默忽略（额度表只服务已知模板；恶意/脏上报不落库）
            log.warn("wx subscribe grant ignored (unknown templateId, userId={})", userId);
            return ApiResponse.ok(null);
        }
        wxSubscribeService.recordGrant(userId, request.templateId());
        return ApiResponse.ok(null);
    }

    /**
     * 当前用户订阅额度状态（GET /user/wx-subscribe-status）。
     * 三态判据（前端菜单/提示条渲染）：granted==0 未授权过；available>0 可发；
     * available==0 且 granted>0 额度已用完（可再授权补充）。
     */
    @GetMapping("/wx-subscribe-status")
    public ApiResponse<WxSubscribeStatusResponse> status() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(wxSubscribeService.queryStatus(userId, statusTemplateId));
    }
}
