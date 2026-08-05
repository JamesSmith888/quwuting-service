package org.quwuting.quwutingservice.venuefeedback.enums;

/**
 * 用户上报类型（通用上报模板的类型维度，2026-08-05 演进）。
 * <p>
 * 本模块从"场所信息纠错反馈"泛化为**统一用户上报**：门票/舞伴等价格信息缺失
 * （PRICE）、状态疑似错误（SUSPENDED/CLOSED_DOWN）、一般信息有误（INACCURATE）、
 * 其他（OTHER）等场景共用同一张表、同一个提交接口与同一套管理端处理流程。
 * 新增上报场景 = 扩展枚举值 + 前端入口，无需新建表/接口——这是"通用模板"的
 * 可扩展性保证（见后端 AGENTS.md「统一用户上报（venuefeedback）」章节）。
 * <p>
 * 与 venuestatusreport（实时 4h TTL 众包信号）的边界保持：本模块是异步管理员
 * 审核流程，实时信号职责不在此承担（见 AGENTS.md「场所状态上报」章节）。
 */
public enum FeedbackType {
    CLOSED_DOWN("门店已关门/停业"),
    SUSPENDED("门店暂停营业"),
    INACCURATE("信息有误"),
    /** 门票/舞伴等价格费用信息缺失或有误（数据缺失空态的上报入口，详情页触发） */
    PRICE("价格信息缺失或有误"),
    OTHER("其他问题");

    private final String displayName;

    FeedbackType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
