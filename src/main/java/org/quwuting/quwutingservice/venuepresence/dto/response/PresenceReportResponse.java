package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 到访痕迹上报响应（2026-09-29 V33）。客户端 fire-and-forget（失败静默不重试），
 * 响应只承载「是否落库」与服务端裁决原因，供联调与埋点观察。
 *
 * @param accepted true = 本次上报已写入（或命中桶幂等吸收）；false = 服务端裁决不收
 * @param reason   accepted=false 时的原因码（{@link #REASON_DISABLED} / {@link #REASON_CONSENT_REQUIRED}）；null = 无
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PresenceReportResponse(boolean accepted, String reason) {

    /** 运营开关关闭时的固定原因（对应 opsconfig 键 presence.collect.enabled） */
    public static final String REASON_DISABLED = "DISABLED";

    /**
     * 该用户没有有效的显式同意（2026-10-03 五轮门禁）：从未确立 / 最新态关闭 / 最新态仍是
     * 历史 DEFAULT（默认开启期未经询问）。旧版默认开启端的 ping 由此在上线即被拒收，
     * 不依赖端上升级覆盖率；非错误码——旧端对失败静默，不应收到 4xx/5xx 噪音。
     */
    public static final String REASON_CONSENT_REQUIRED = "CONSENT_REQUIRED";
}
