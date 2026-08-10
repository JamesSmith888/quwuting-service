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
    /**
     * 门店已恢复营业（2026-08-10 新增）：与 CLOSED_DOWN/SUSPENDED 相反的纠正信号。
     * <p>
     * 触发场景 = 门店存储态为「已停业」（CEASED）时，用户现场确认已重新开业——
     * 详情页报告操作 chip 翻转为「报告恢复营业」，提交本类型走异步管理员审核，
     * 管理员核实后可经 updateVenue 将状态改回 OPEN（恢复通道 = 既有 updateVenue，
     * 与暂停报采纳 markSuspendedByReport 对称）。
     * <p>
     * 为什么走 venuefeedback 而非 venuestatusreport：纠正的是**存储态**
     * （CEASED→OPEN），属异步审核职责；4h TTL 实时信号层对"已停业"门店无决策意义
     * （详情见前端 AGENTS.md「场所状态上报交互规范 → 报告操作状态机」）。
     */
    RESUMED("门店已恢复营业"),
    INACCURATE("信息有误"),
    /**
     * 场地数据字段缺失（营业时间/联系方式/地址/简介/微信联系等，2026-08-06 新增）——
     * 详情页"信息缺失？点此上报"入口统一使用；note 承载缺失字段说明，
     * 用户可在提交前于输入框补充具体数据（如实际营业时间/联系电话）。
     * 与 PRICE（价格类）并列：PRICE 是价格专属类型，本类型覆盖其余数据字段缺失场景。
     */
    MISSING_INFO("信息缺失"),
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
