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
 * <p>
 * <b>状态类类型下线（2026-08-20）</b>：SUSPENDED/CLOSED_DOWN/RESUMED 的<b>提交入口</b>
 * 已随前端反馈面板白名单收敛而关闭——「报告暂停营业/报告恢复营业」统一走
 * venuestatusreport 实时信号通道（ReportType.SUSPENDED/RESUMED，提交即回显、
 * 采纳联动门店状态）。本枚举保留三类值仅用于<b>历史数据兼容</b>（「我的上报记录」/
 * 管理端列表仍能识别与处置）；处置兜底：SUSPENDED/RESUMED 采纳时联动门店营业状态
 * （{@code VenueFeedbackService.adoptReport}，与 status-reports 采纳同一联动通道），
 * CLOSED_DOWN 停业认定较重，由管理员经 updateVenue 手动执行。新增状态类上报场景
 * 禁止回到本通道（见 AGENTS.md「报告操作状态机」根因链）。
 */
public enum FeedbackType {
    /** 门店已关门/停业（状态类，2026-08-20 提交入口下线，仅历史数据兼容；停业认定由管理员经 updateVenue 手动执行） */
    CLOSED_DOWN("门店已关门/停业"),
    /** 门店暂停营业（状态类，2026-08-20 提交入口下线，仅历史数据兼容；采纳联动 markSuspendedByReport） */
    SUSPENDED("门店暂停营业"),
    /**
     * 门店已恢复营业（2026-08-10 新增；2026-08-20 提交入口下线，仅历史数据兼容）。
     * <p>
     * 历史语义：门店存储态**声称非营业**（RENOVATING/CLOSED/SUSPENDED/CEASED）时
     * 用户提交本类型纠正存储态（非营业→OPEN）。2026-08-20 起该场景统一走
     * venuestatusreport 实时信号通道（ReportType.RESUMED，采纳联动 reopenByReport）——
     * 根因：旧异步通道与公告页「最近的突发事件」（status-reports 表）互不相通，
     * 提交后无回显、采纳不改门店状态（见 AGENTS.md「报告操作状态机」根因链）；
     * 本类型采纳时仍做 reopenByReport 兜底联动（防御历史数据与 API 直调）。
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
