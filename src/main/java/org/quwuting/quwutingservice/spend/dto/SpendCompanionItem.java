package org.quwuting.quwutingservice.spend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 账目上一位「一同计时的人」的展示快照（2026-10-09，V47；上行 sync 与下行 entries 共用同一形状）。
 * <p>
 * <b>只有三项，且没有对方 userId</b>：昵称 / 头像是「会话内静态标识」的展示口径（与计时分享域
 * {@code TimerShareProfileView} 同款字段），关系是 HOST / JOINER 两值（见
 * {@link org.quwuting.quwutingservice.spend.enums.SpendCompanionRelation}）。不下发 userId 是为了
 * 不给「跨账目串联同一个人」留通道——本功能的展示不需要它。
 * <p>
 * {@code nickname} / {@code avatarUrl} 的 null 是<b>有语义的</b>（对方没设置昵称 / 头像），客户端负责
 * 兜底占位，服务端不做展示层兜底，故 {@code ALWAYS} 显式写出（全局 non_null 下客户端才能区分
 * 「null」与「字段缺失」）。
 *
 * @param nickname  昵称快照；null = 对方未设置
 * @param avatarUrl 头像 URL 快照；null = 对方未设置
 * @param relation  关系协议值（HOST / JOINER，宽容大小写，非法值该项被丢弃）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SpendCompanionItem(String nickname, String avatarUrl, String relation) {
}
