package org.quwuting.quwutingservice.wxsubscribe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 订阅授权额度上报请求体（前端 wx.requestSubscribeMessage 返回 accept 后
 * fire-and-forget 上报）。
 */
public record WxSubscribeGrantRequest(

        /** 订阅消息模板 ID（服务端仅认配置模板 wechat.subscribe.status-template-id，其余静默忽略） */
        @NotBlank
        @Size(max = 64)
        String templateId) {
}
