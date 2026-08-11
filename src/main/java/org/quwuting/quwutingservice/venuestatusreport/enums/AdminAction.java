package org.quwuting.quwutingservice.venuestatusreport.enums;

/**
 * 管理端处置标记（2026-08-11 新增，区分"已采纳"与"已移除"两种 soft delete 语义）。
 * <p>
 * 旧实现采纳与移除同为 {@code deleted=true}，公开视图无法区分"已核实"与"已清理"。
 * 紧急公告区需要展示"已核实"（采纳后公告保留至 TTL 过期，带核实标记），而移除的
 * 信号立即从公开视图消失——故引入本列区分两种处置。null = 未处置（活跃信号）。
 * <p>
 * 用户重新上报（upsert 恢复软删记录）时本列重置为 null。
 */
public enum AdminAction {
    /** 已采纳：管理员核实属实（公告区保留展示至 TTL 过期，带"已核实"标记） */
    ADOPTED,
    /** 已移除：平台清理虚假/失效信号（公开视图即时消失） */
    REMOVED
}
