package org.quwuting.quwutingservice.venue.enums;

/**
 * 场所状态可信度等级。
 * <p>
 * 由 {@code VenueHeatService} 基于三维矩阵派生：当前状态类型（营业中 vs 非营业）×
 * 近30天暂停次数 × 状态持续天数；活跃用户报告（TTL 内）override 为 LOW。
 * 判定逻辑与结论文案（statusConfidenceText / statusConfidenceRuleDetail）的唯一事实源
 * 在 VenueHeatService，前端据此渲染颜色分级与行动建议，不持有判定逻辑与文案。
 * <p>
 * 矩阵逻辑（2026-08-08 三维化，修复"已停业门店判稳定营业"的语义错配）：
 * <ul>
 *   <li>营业中（OPEN）：0 次暂停 → HIGH；≥1 次暂停 + 持续 ≤7天 → MEDIUM；≥1 次暂停 + 持续 &gt;7天 → LOW</li>
 *   <li>非营业（已停业/暂停/装修/休息）：持续 &gt;7天 → HIGH（状态被时间验证）；持续 ≤7天 → MEDIUM（建议确认）</li>
 *   <li>活跃报告 override：任何状态，TTL 内有用户报告 → LOW</li>
 * </ul>
 */
public enum StatusConfidence {
    HIGH("状态可信"),
    MEDIUM("建议确认"),
    LOW("数据可能过时");

    private final String displayName;

    StatusConfidence(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
