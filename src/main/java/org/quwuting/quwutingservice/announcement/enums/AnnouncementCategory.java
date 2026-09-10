package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告/快讯分类（2026-09-01 建立；2026-09-10 扩 FLASH 行业快讯）。
 * <p>
 * 三个值分属两个语义域：共用一张表，但走各自的接口与入口。
 * <ul>
 *   <li>{@link #NOTICE} / {@link #DATA_UPDATE} —— <b>公告域</b>：平台权威内容
 *       （运营公告 / 数据更新），强触达（首页悬浮条 + 红点 + 已读回执），
 *       平台为内容真实性背书（docs/agents/34）；DATA_UPDATE 的同日防重唯一键
 *       仅作用于 SYSTEM + DATA_UPDATE 组合（见 V7 迁移生成列）；</li>
 *   <li>{@link #FLASH} —— <b>快讯域</b>：行业情报（停业 / 开闭店 / 时段调整），
 *       弱触达（tabBar 独立入口 + 列表，无红点无已读），标注来源、平台不背书
 *       （docs/agents/47-bulletins.md）。</li>
 * </ul>
 * <p>
 * <b>互斥契约</b>：公告侧全部查询（用户端可见列表 / 未读数 / 管理端列表）必须
 * 显式排除 {@code FLASH}，快讯侧则只取 {@code FLASH}——见
 * {@code AnnouncementRepository} 的 {@code excludeCategory} / {@code category}
 * 参数，保证两类内容不互相串台。
 */
public enum AnnouncementCategory {
    NOTICE,
    DATA_UPDATE,
    FLASH
}
