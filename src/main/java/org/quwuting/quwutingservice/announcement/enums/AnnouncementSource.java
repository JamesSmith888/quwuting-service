package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告来源（2026-09-01）：MANUAL 管理员人工发布 / SYSTEM 系统自动生成。
 * <p>
 * 两个场景共用一套公告系统，差异仅此字段：
 * <ul>
 *   <li>MANUAL：管理后台公告模块人工创建（创建接口只允许 MANUAL）；</li>
 *   <li>SYSTEM：数据更新钩子（venuesync 写库成功）经 {@code createSystem} 内部
 *       调用自动生成，不暴露管理端创建入口；operator_id 恒 NULL
 *       （对齐 Agent 来源审计先例）。</li>
 * </ul>
 */
public enum AnnouncementSource {
    MANUAL,
    SYSTEM
}
