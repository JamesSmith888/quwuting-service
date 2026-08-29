package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 营业状态变更记录条目（2026-08-29 新增，营业状态详情弹窗「状态记录」区块数据源）。
 * <p>
 * 数据源 = qwt_venue_status_logs（近30天窗口，最多 5 条，按变更时间倒序）。
 * 事件文案（changeText）由后端生成下发——与可信度判定依据同模式，文案唯一事实源
 * 在后端，前端只渲染；日期格式 MM-dd（30 天窗口内无年份歧义风险由窗口边界兜底）。
 */
public record VenueStatusLogItem(
        /** 变更日期（MM-dd） */
        String changedAt,
        /** 变更事件文案（如「暂停营业 → 营业中」；建档行为「初始状态：营业中」） */
        String changeText
) {}
