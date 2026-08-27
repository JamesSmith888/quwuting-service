package org.quwuting.quwutingservice.dancer.enums;

/**
 * 客人反馈枚举（2026-08-27 新增，V56；docs/agents/25-invite-lifecycle-and-feedback.md）。
 * <p>
 * 语义：非中转舞伴（contact_relay=false，平台不感知线下结果）的客人遇到
 * 「没加上 TA / 被 TA 拒绝 / 未回复」时一键反馈——平台感知真实世界结果 +
 * 自动返还该邀约的原扣费积分（幂等）。管理端邀约工作台据此识别需人工介入
 * 的邀约（客人反馈标记）。
 * <p>
 * 枚举列禁 CHECK 约束（项目约定），应用层解析防御。
 */
public enum DemandGuestFeedback {

    /** 加了没加上（微信搜不到/对方未通过等） */
    ADD_FAILED("没加上"),

    /** 被 TA 拒绝（微信添加被拒/明确表示不方便） */
    REJECTED("被 TA 拒绝"),

    /** 添加后 TA 未回复 */
    NO_REPLY("TA 未回复"),

    /** 其他 */
    OTHER("其他");

    private final String label;

    DemandGuestFeedback(String label) {
        this.label = label;
    }

    /** 短标签（前端镜像字典 + 管理端展示；客人侧文案前端零拼接） */
    public String label() {
        return label;
    }

    /** 解析反馈 code（非法/空 → null，防御历史脏数据） */
    public static DemandGuestFeedback parseOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return DemandGuestFeedback.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
