package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 计时分享会话内一方的公开资料（昵称 / 头像；2026-10-08，V45；文档 = 54 号 §资料互看）。
 * <p>
 * <b>范围刻意的窄</b>：只有这两项。它们既是最小可识别信息（"我看到的是谁"），也是平台上
 * 已有先例的展示口径（门店热度上报列表 / 点赞名单同款字段）——年龄 / 性别 / 城市等资料
 * 一概不经本通道下发。用户对资料的修改只影响之后的新会话（快照语义，与门店名快照同口径）。
 * <p>
 * {@code nickname} / {@code avatarUrl} 的 null 是<b>有语义的</b>（用户没设置昵称 / 没设头像），
 * 客户端负责兜底展示（默认昵称 + 首字占位）——服务端不做展示层兜底（"微信用户"是客户端
 * 文案，不是数据）。
 *
 * @param nickname  昵称；null = 未设置（客户端兜底）
 * @param avatarUrl 头像 URL；null = 未设置（客户端以昵称首字占位）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TimerShareProfileView(String nickname, String avatarUrl) {
}
