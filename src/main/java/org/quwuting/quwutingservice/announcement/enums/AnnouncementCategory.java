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
    FLASH;

    /**
     * 分类对应的缺省触达等级（<b>唯一映射点</b>，2026-09-15，见
     * {@link AnnouncementTouchLevel}）：调用方一律走本方法，禁自行 if/else 判断分类。
     * <ul>
     *   <li>{@link #NOTICE} → {@link AnnouncementTouchLevel#ALERT}：运营公告按定义
     *       是"需要用户知晓的变更"，缺省即打断（发布侧可显式下调）；</li>
     *   <li>{@link #DATA_UPDATE} → {@link AnnouncementTouchLevel#SILENT}：数据更新 /
     *       每日舞讯是流水记录，可查即可，不计未读（发布侧可显式上调）；</li>
     *   <li>{@link #FLASH} → {@link AnnouncementTouchLevel#SILENT}：快讯域本就无已读
     *       回执（靠 {@code excludeCategory=FLASH} 排除在未读之外），取 SILENT 与之一致。</li>
     * </ul>
     * 缺省值让"自动化发布的链路不传该字段也能落在正确档位"——否则每加一条发布通道
     * 都要记得补参数，漏一处就复发旧问题。
     */
    public AnnouncementTouchLevel defaultTouchLevel() {
        return this == NOTICE ? AnnouncementTouchLevel.ALERT : AnnouncementTouchLevel.SILENT;
    }
}
