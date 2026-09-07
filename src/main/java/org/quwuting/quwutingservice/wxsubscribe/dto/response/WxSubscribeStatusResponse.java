package org.quwuting.quwutingservice.wxsubscribe.dto.response;

/**
 * 当前用户订阅消息额度状态（GET /user/wx-subscribe-status，2026-09-07）。
 * <p>
 * 前端据此渲染「微信提醒」三态（门店更多菜单/通知落地提示条）：
 * grantedCount == 0 → 从未授权（未开启）；availableCount &gt; 0 → 已开启；
 * availableCount == 0 且 grantedCount &gt; 0 → 额度已用完（可再授权补充）。
 * 注：本地账本仅代理微信侧真实额度（43101 清零对账收敛漂移）。
 */
public record WxSubscribeStatusResponse(
        /** 订阅消息模板 ID（当前唯一模板，前端展示用） */
        String templateId,
        /** 剩余可发送额度 */
        int availableCount,
        /** 历史授权累计次数（区分「从未授权」与「已用完」） */
        int grantedCount) {
}
