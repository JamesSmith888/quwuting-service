package org.quwuting.quwutingservice.venue.enums;

/**
 * 场所状态可信度等级。
 * <p>
 * 由 {@code VenueHeatService} 基于「当前状态持续天数」与「近30天暂停次数」二维矩阵派生，
 * 前端据此渲染颜色分级与行动建议。
 * <p>
 * 矩阵逻辑：
 * <ul>
 *   <li>稳定（30天内0次暂停）→ 恒为 HIGH（无论状态持续多久，不更新≠不准确）</li>
 *   <li>不稳定 + 状态持续 ≤7天 → MEDIUM（近期有变动，但刚确认过）</li>
 *   <li>不稳定 + 状态持续 >7天 → LOW（多变且长时间未确认，数据可能过时）</li>
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
