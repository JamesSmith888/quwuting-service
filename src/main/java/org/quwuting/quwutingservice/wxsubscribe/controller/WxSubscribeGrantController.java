package org.quwuting.quwutingservice.wxsubscribe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.wxsubscribe.dto.request.WxSubscribeGrantRequest;
import org.quwuting.quwutingservice.wxsubscribe.service.WxSubscribeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信订阅消息授权上报（2026-09-07 新增，均需登录）。
 * <p>
 * POST /user/wx-subscribe-grants — 前端 wx.requestSubscribeMessage 返回 accept
 * 后 fire-and-forget 上报，服务端 upsert 累加一条发送额度（额度模型见
 * {@code WxSubscribeQuotaRepository} 注释）。reject/ban 不上报（无额度语义，
 * 零增量）；非配置模板静默忽略（防任意模板灌脏数据）。
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
}
