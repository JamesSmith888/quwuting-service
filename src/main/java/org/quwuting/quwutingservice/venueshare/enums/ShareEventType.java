package org.quwuting.quwutingservice.venueshare.enums;

/**
 * 分享事件类型（事件日志语义，非状态机）。
 * <ul>
 *   <li>SHARE — 分享者发起了分享（分享动作，支撑邀请排行 / 热门传播门店分析）</li>
 *   <li>OPEN — 被分享者通过分享卡片打开了详情页（携带 shareFrom 归因，支撑回流 / 舞友关系分析）</li>
 * </ul>
 */
public enum ShareEventType {
    SHARE,
    OPEN
}
