package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告状态机（2026-09-01）：DRAFT → PUBLISHED → OFFLINE。
 * <p>
 * 流转规则（docs/agents/34）：
 * <ul>
 *   <li>create 默认落 DRAFT（草稿）；</li>
 *   <li>publish 立即生效或按 publishAt 定时（未到时间保持 DRAFT，由 @Scheduled 到点强转）；</li>
 *   <li>offline 置 OFFLINE（终态之一）；下线后不可直接回 PUBLISHED（需重新 publish）；</li>
 *   <li>任意状态可软删除。</li>
 * </ul>
 * 枚举列用 STRING 存储（禁 CHECK，扩枚举免迁移，对齐 ReportStatus 先例）。
 */
public enum AnnouncementStatus {
    DRAFT,
    PUBLISHED,
    OFFLINE
}
