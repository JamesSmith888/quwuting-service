package org.quwuting.quwutingservice.dancershare.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * 记录分享动作请求体（POST /dancers/{id}/shares，镜像场所分享）。
 * channel 为分享发起渠道（BUTTON / MENU / TIMELINE），
 * 非法值由 @Pattern 校验拒绝（400），防止脏数据入库。
 */
public record RecordShareRequest(
        @Pattern(regexp = "^(BUTTON|MENU|TIMELINE)$", message = "无效的分享渠道")
        String channel
) {
}
