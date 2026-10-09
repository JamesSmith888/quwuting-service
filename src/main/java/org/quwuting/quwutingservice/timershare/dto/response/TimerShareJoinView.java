package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 主持方轮询响应里的一位加入者（2026-10-08，V45；文档 = 54 号 §资料互看 + §结算同步）。
 * <p>
 * 身份口径：<b>不向主持方下发加入者的 userId</b>——会话内的展示只需"第几位 + 昵称 + 头像"，
 * 下发 ID 只会给"跨会话串联同一个人"留出通道（本功能的用途不需要它）。序号 {@code seq}
 * 是加入顺序（1 起），与主持方界面上的「第 N 位」直接对应。
 *
 * @param seq               加入序号（1 起，按加入先后）
 * @param nickname          昵称（null = 未设置，客户端兜底）——服务端每次现查当前值（非快照）
 * @param avatarUrl         头像（null = 未设置，客户端以昵称首字占位）
 * @param settledAtMs       该加入者的结算时刻（服务端时间轴 epoch ms）；null = 尚未结算
 * @param settledNetSeconds 该加入者结算时的净时长（秒）；null = 尚未结算。只作展示，不含金额
 * @param joinedAtMs        该加入者扫码加入的时刻（服务端时间轴 epoch ms；2026-10-09，计时页「同行详情」展示
 *                          「几点加入」用）。取自加入流水的创建时间，与 settledAtMs 同一条时间轴，客户端用同一次
 *                          往返校准换算回本机
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TimerShareJoinView(
        int seq,
        String nickname,
        String avatarUrl,
        Long settledAtMs,
        Integer settledNetSeconds,
        Long joinedAtMs) {
}
