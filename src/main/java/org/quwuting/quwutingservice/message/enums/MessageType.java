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
    DANCER_STATUS
}
