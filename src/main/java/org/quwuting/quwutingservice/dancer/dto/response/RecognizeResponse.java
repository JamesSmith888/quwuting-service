package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 认可 toggle 的即时响应（前端据此本地更新，无需整页刷新）。
 * recognized = 服务端确认后的最终参与态（true=今日已认可，false=今日已取消）。
 * stats 为当前舞伴的最新四窗口统计——每日一记模型下取消只作用于当日记录，
 * 各窗口计数在服务端确认后即为真实值，前端可直接覆盖本地副本（同 Reaction 的
 * 快照同步语义，见 {@code recordReactionSync}）。
 */
public record RecognizeResponse(
        boolean recognized,
        DancerRecognitionStats stats
) {}
