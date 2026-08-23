package org.quwuting.quwutingservice.tagdict.enums;

/**
 * 标签字典适用领域（2026-08-24 通用标签字典的 scope 维度）。
 * <ul>
 *   <li>{@code DANCER}：舞伴资料标签（线上/线下/龙女…，管理员在舞伴新增/编辑表单选择）；</li>
 *   <li>{@code VENUE}：门店标签预留——门店未来可从「qwt_venues.tags 自由文本列」迁移到
 *       本字典（同一套接口即「标签系统套用门店」的落点，见 AGENTS.md「标签字典」）。</li>
 * </ul>
 */
public enum TagScope {
    DANCER,
    VENUE
}
