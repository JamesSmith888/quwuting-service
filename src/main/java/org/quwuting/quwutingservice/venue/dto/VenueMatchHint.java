package org.quwuting.quwutingservice.venue.dto;

import org.quwuting.quwutingservice.venue.enums.VenueMatchField;

/**
 * 列表搜索「匹配解释」载荷（2026-09-16 通用化，见 {@code VenueMatchField} 与
 * {@code docs/agents/38-venue-aliases.md} §4.1「命中即解释」）。
 * <p>
 * <b>判据（本域的根判据）＝匹配可自证</b>：搜索结果的每一条，用户都应该能在卡片上
 * 自己找到他输的那个词。搜不到用户以为平台没收录，搜到了却在卡片上找不到输入词，
 * 用户会认为平台数据错了。
 * <p>
 * <b>语义边界（严禁扩散）</b>：
 * <ul>
 *   <li><b>单值 + 条件下发</b>：仅当本次 keyword 确实命中了该店某个「卡片不可见」载体时
 *       非 null；无 keyword / 命中来源全部可在卡片上自证时为 null——非命中门店
 *       <b>零带宽、零布局变化</b>（前端条件渲染，条件行不占骨架屏）；</li>
 *   <li><b>只补展示、不改结果集</b>：命中集唯一由 {@code KW_MATCH} 决定，本载荷由 Service 层
 *       对当页门店内存二次判定装配——判定不中的最坏后果仅为「该店少一行解释」，绝不漏店；</li>
 *   <li><b>{@link #text} 的两种取值都要消费</b>：非 null = 可直接展示的原文（前端按
 *       keyword 高亮）；<b>null = 该载体确实命中、但其原文不可公开展示</b>（当前仅一种情形：
 *       城市级地址类型——歌友会——的详细地址被脱敏，见 {@code VenueResponseMapper}
 *       的地址脱敏闸门）。前端据此渲染「需联系获取」类提示，<b>禁止</b>把 null 当作
 *       "无解释"而整行不渲染（那会让用户回到完全懵的状态）。</li>
 * </ul>
 *
 * @param field 命中载体（只可能是卡片上不可见的四类之一）
 * @param text  可展示的解释原文；null = 命中但不可公开展示（城市级地址）
 */
public record VenueMatchHint(VenueMatchField field, String text) {}
