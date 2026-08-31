package org.quwuting.quwutingservice.venue.dailyopening.enums;

/**
 * 信息源门店与平台门店的匹配置信度（镜像 quwuting-ops 管线侧 MatchConfidence）。
 * <p>
 * 驱动写库策略（对齐平台「确认态 / 未经核实」哲学）：
 * <ul>
 *   <li>EXACT / ALIAS —— 可信匹配，可自动执行状态反转（CEASED/SUSPENDED → OPEN）；</li>
 *   <li>CONTAINED / FUZZY —— 存在误配风险（如「小马」命中酒吧），仅落快照、不反转，
 *       由人工复核后（改 confidence 或直接走管理端）处理。</li>
 * </ul>
 */
public enum DailyOpeningConfidence {
    EXACT,
    ALIAS,
    CONTAINED,
    FUZZY
}
