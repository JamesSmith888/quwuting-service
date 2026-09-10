package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告/快讯来源（2026-09-01 建立；2026-09-10 扩 AGENT 快讯 Agent 发布通道）。
 * <p>
 * 三类内容共用一套底层系统，差异仅此字段：
 * <ul>
 *   <li>MANUAL：管理后台公告/快讯模块人工创建（创建接口只允许 MANUAL）；</li>
 *   <li>SYSTEM：数据更新钩子（venuesync 写库成功）经 {@code createDataUpdateAnnouncement}
 *       内部调用自动生成，不暴露管理端创建入口；operator_id 恒 NULL
 *       （对齐 Agent 来源审计先例）；</li>
 *   <li>AGENT：快讯域 Agent 发布通道（{@code POST /admin/bulletins/agent-publish}）
 *       专用。<b>由服务端固定置值，请求体不接受调用方指定</b>（防伪造来源）；
 *       operator_id 记调用管理员审计，dedup_key 提供重跑幂等（docs/agents/47）。</li>
 * </ul>
 */
public enum AnnouncementSource {
    MANUAL,
    SYSTEM,
    AGENT
}
