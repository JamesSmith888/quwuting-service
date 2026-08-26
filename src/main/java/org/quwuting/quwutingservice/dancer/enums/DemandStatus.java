package org.quwuting.quwutingservice.dancer.enums;

/**
 * 邀约状态（2026-08-26 新增，V50；qwt_demand_records.status）。
 * <p>
 * 语义：邀约中转与自动降级状态机（docs/agents/22-invite-relay-and-auto-release.md）——
 * 开启中转开关（contact_relay）的舞伴，客人提交邀约后进入 PENDING，由平台
 * 管理员微信人工转发给舞伴，舞伴回「给/不给」后管理员一键发放/拒绝；24 小时
 * 无回复按 auto_release 开关自动降级（AUTO_RELEASED 自动发放 / EXPIRED 告知
 * 未回复）。存量记录（V42 前无状态列）status 为 NULL，语义等价 APPROVED
 * （历史客人在当时已拿到微信），前端徽标兼容不渲染。
 * <p>
 * 枚举列禁 CHECK 约束（项目约定），应用层解析防御。
 */
public enum DemandStatus {

    /** 邀约已提交，等待舞伴回复（管理员中转中） */
    PENDING("等待回复"),

    /** 舞伴同意，管理员已发放（客人可查看联系方式） */
    APPROVED("已同意"),

    /** 舞伴拒绝（客人侧文案 =「TA 暂时不方便接收邀约」） */
    REJECTED("暂时不方便"),

    /** 24h 无回复且 auto_release=true，平台自动发放（兜底，不卡单） */
    AUTO_RELEASED("已自动发放"),

    /** 24h 无回复且 auto_release=false，告知客人暂未回复 */
    EXPIRED("暂未回复");

    private final String label;

    DemandStatus(String label) {
        this.label = label;
    }

    /** 状态短标签（管理端/列表徽标；客人终态长文案由 statusText 另行派生） */
    public String label() {
        return label;
    }

    /**
     * 客人侧状态文案（服务端权威，前端零拼接；<b>尊重友好原则</b>——终态中性
     * 表述 + 下一步出路引导见 22 号文档「客人侧体验原则」）。
     */
    public String statusText() {
        return switch (this) {
            case PENDING -> "邀约已送达，TA 会在 24 小时内回复你";
            case APPROVED -> "TA 已同意，微信已展示，快去添加好友吧";
            case REJECTED -> "TA 暂时不方便接收邀约，你可以看看其他舞伴";
            case AUTO_RELEASED -> "TA 未及时回复，平台已为你展示微信，快去添加好友吧";
            case EXPIRED -> "TA 可能暂时没看到，你可以稍后再试，或看看其他舞伴";
        };
    }

    /** 是否已发放联系方式（客人可查看） */
    public boolean released() {
        return this == APPROVED || this == AUTO_RELEASED;
    }

    /** 是否终态（不再流转；PENDING 为唯一可流转态） */
    public boolean terminal() {
        return this != PENDING;
    }

    /** 解析状态代码（非法/空 → null，防御历史脏数据） */
    public static DemandStatus parseOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return DemandStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
