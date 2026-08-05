package org.quwuting.quwutingservice.venuefeedback.enums;

/**
 * 用户上报处理状态（管理端状态机）。
 * <p>
 * 单道上报的终态流转：PENDING（待处理）→ RESOLVED（已处理）/ DISMISSED（已忽略）。
 * 终态固定不可回退：RESOLVED 表示管理员已核实并完成数据维护，DISMISSED 表示
 * 判定为误报/无需处理。两个终态都是管理动作的明确落点，避免"已处理"布尔
 * 无法区分"处理完"与"忽略掉"两种语义。
 */
public enum ReportStatus {
    PENDING("待处理"),
    RESOLVED("已处理"),
    DISMISSED("已忽略");

    private final String displayName;

    ReportStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
