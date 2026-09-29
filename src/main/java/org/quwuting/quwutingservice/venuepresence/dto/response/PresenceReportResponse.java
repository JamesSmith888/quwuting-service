package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 到访痕迹上报响应（2026-09-29 V33）。客户端 fire-and-forget（失败静默不重试），
 * 响应只承载「是否落库」与服务端裁决原因，供联调与埋点观察。
 *
 * @param accepted true = 本次上报已写入（或命中桶幂等吸收）；false = 服务端已停采
 * @param reason   accepted=false 时的原因码（DISABLED = 运营开关关闭）；null = 无
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PresenceReportResponse(boolean accepted, String reason) {

    /** 运营开关关闭时的固定原因（对应 opsconfig 键 presence.collect.enabled） */
    public static final String REASON_DISABLED = "DISABLED";
}
