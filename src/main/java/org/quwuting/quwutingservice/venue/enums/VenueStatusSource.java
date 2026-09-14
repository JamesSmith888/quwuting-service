package org.quwuting.quwutingservice.venue.enums;

/**
 * 门店状态值「归谁所有」（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 与 {@code qwt_venue_status_logs.change_source} 分工不重叠，切勿混用：
 * <ul>
 *   <li>{@code VenueStatusLog.changeSource} 记的是<b>谁写的</b>（通道标签：
 *       AGENT_BATCH / ADMIN / null），只用于审计与「更新记录」展示；</li>
 *   <li>本枚举记的是<b>这个值代表谁的判断</b>（所有权），是权威层级门禁的判定依据。</li>
 * </ul>
 * 曾经这两件事被挤在同一个 {@code changeSource} 标签里：Web 后台「同步报告勾选应用」
 * 打的标是 {@code ADMIN}，但它其实是「人工背书的外部舞讯信息」，不等于人工直改状态。
 */
public enum VenueStatusSource {

    /**
     * 人工直改（管理端门店编辑表单 / 门店认领人编辑 / 采纳用户上报）。
     * 写入时同时打「人工锁」——锁内外部舞讯通道不得覆盖。
     */
    MANUAL("人工"),

    /**
     * 外部舞讯推断（Agent 每日批量 / 人工确认的同步条目）。
     * 写入成功即接管所有权并清除人工锁。
     */
    SYNC("自动");

    private final String displayName;

    VenueStatusSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
