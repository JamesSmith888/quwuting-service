package org.quwuting.quwutingservice.dancer.enums;

/**
 * 邀约拒绝原因（2026-08-27 新增，V55；qwt_demand_records.reject_reason）。
 * <p>
 * 语义：管理员按舞伴微信回复「不给」拒绝邀约时选填的原因标签——<b>拒绝 = 信息
 * 而非句号</b>（docs/agents/24-rejection-and-matching.md「P0 拒绝原因闭环」）：
 * <ul>
 *   <li>客人侧：展示知因文案 {@link #guestText()}（「TA 暂时不方便（档期冲突）」）
 *       ——客人不再归因自己（「我是不是说错话」），知因减痛；</li>
 *   <li>管理端：工作台已处理视图展示原因标签 + 撮合台帮客人找替代时避开同因舞伴
 *       （档期冲突 → 找别的舞伴；暂不接新客 → 找接新客的舞伴）。</li>
 * </ul>
 * 客人侧文案 = 枚举权威派生（前端零拼接，尊重友好原则）；管理端标签 =
 * {@link #label()}（前端镜像字典，选择/展示用）。NULL = 未填原因（存量兼容，
 * 客人侧回退 DemandStatus.statusText 通用文案）。
 * <p>
 * 枚举列禁 CHECK 约束（项目约定），应用层解析防御（parseOrNull）。
 */
public enum DemandRejectReason {

    /** 舞伴档期冲突（时间上排不开） */
    SCHEDULE_CONFLICT("档期冲突"),

    /** 距离太远（非同城/服务半径外） */
    DISTANCE_TOO_FAR("距离太远"),

    /** 需求类型与舞伴服务范围不符 */
    SERVICE_MISMATCH("需求类型不符"),

    /** 舞伴暂不接新客（只接熟客） */
    NOT_ACCEPTING_NEW("暂不接新客"),

    /** 其他（未列出的原因） */
    OTHER("其他");

    private final String label;

    DemandRejectReason(String label) {
        this.label = label;
    }

    /** 原因短标签（管理端展示/选择；前端镜像字典 DEMAND_REJECT_REASONS 同源） */
    public String label() {
        return label;
    }

    /**
     * 客人侧知因文案（服务端权威，前端零拼接；尊重友好原则——中性表述 +
     * 下一步出路引导，替代 DemandStatus.statusText 的通用「暂时不方便」表述）。
     */
    public String guestText() {
        return "TA 暂时不方便（" + label + "），你可以看看其他舞伴";
    }

    /** 解析原因代码（非法/空 → null，防御历史脏数据/旧客户端） */
    public static DemandRejectReason parseOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return DemandRejectReason.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
