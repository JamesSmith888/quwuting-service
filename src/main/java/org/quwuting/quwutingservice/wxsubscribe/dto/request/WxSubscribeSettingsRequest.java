package org.quwuting.quwutingservice.wxsubscribe.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 微信通知设置请求体（POST /user/wx-subscribe-settings，2026-09-08 新增，V13）。
 * <p>
 * batchLimit = 突发窗口（默认 3 分钟）内最多下发的微信通知条数：
 * 3（默认）/ 5（重度）/ 0（不限，接收全部门店变动）。合法值由
 * {@code WxSubscribeService#updateBatchLimit} 校验（非法 → 1021）。
 */
public record WxSubscribeSettingsRequest(

        /** 突发窗口内最多下发的微信通知条数（3 / 5 / 0=不限） */
        @NotNull
        Integer batchLimit) {
}
