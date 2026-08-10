package org.quwuting.quwutingservice.message.enums;

/**
 * 站内信类型（通用消息中心的消息分类，见 AGENTS.md「站内信（消息中心）」）。
 * <p>
 * 新增消息类型 = 在此追加枚举值 + 同步前端 {@code types/message.ts} 的联合类型
 * 与展示文案（code 即唯一标识，前端按 code 渲染图标/文案，禁止前后端各自拼串）。
 */
public enum MessageType {
    /** 舞伴主页审核结果（通过/驳回，驳回附原因） */
    DANCER_REVIEW,
    /** 舞伴主页管理状态变更（隐藏/恢复展示） */
    DANCER_STATUS,
    /**
     * 上报处理结果（2026-08-10 新增，见 AGENTS.md「统一用户上报 → 处理结果站内信」）：
     * 管理端对用户上报（venuefeedback）的实际流转（采纳/采纳不奖励/已处理/忽略）完成时
     * 发送给上报者，同事务、幂等（仅 PENDING→终态实际流转时发一次）；匿名上报不通知。
     * 软关联 VENUE（深链场所详情页）。
     */
    FEEDBACK_RESULT,
    /**
     * 暂停营业报告处理结果（2026-08-10 新增）：管理端采纳暂停报（核实属实并标记门店
     * SUSPENDED + 发积分）时发送给上报者，同事务、幂等（仅活跃→已采纳实际流转时发一次）；
     * 匿名上报不通知。移除（虚假信号）不通知上报者（与用户自撤同语义，见 AGENTS.md
     * 「场所状态上报 · 管理端处置」）。软关联 VENUE（深链场所详情页）。
     */
    STATUS_REPORT_RESULT
}
