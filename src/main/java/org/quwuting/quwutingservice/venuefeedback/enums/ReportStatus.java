package org.quwuting.quwutingservice.venuefeedback.enums;

/**
 * 用户上报处理状态（管理端状态机）。
 * <p>
 * 单道上报的终态流转：PENDING（待处理）→ ADOPTED（已采纳·奖励）/ 
 * ADOPTED_NO_REWARD（已采纳·未奖励）/ RESOLVED（已处理）/ DISMISSED（已忽略）。
 * 终态固定不可回退。
 * <ul>
 *   <li><b>ADOPTED 已采纳</b>（2026-08-10 V2 新增）：管理员核实并采纳该上报（如按
 *       纠错内容更新门店数据，或确认状态异常属实）——**触发积分奖励**（仅登录用户，
 *       匿名上报无法归属；发分与状态流转同事务，见 VenueFeedbackService.adoptReport）；</li>
 *   <li><b>ADOPTED_NO_REWARD 已采纳·未奖励</b>（2026-08-10 管理端三动作定稿新增）：
 *       上报被采纳（数据已更新）但管理员选择不发积分（如上报有效但贡献有限）——
 *       与 RESOLVED（核实后未采纳）语义区分，上报者可见「已采纳·未奖励」；</li>
 *   <li><b>RESOLVED 已处理</b>：管理员完成核实但信息无需采纳应用（如上报营业状态
 *       异常、核实后正常）——不奖励；2026-08-10 起管理端操作区不再提供入口
 *       （由「采纳不奖励」替代），保留为历史状态兼容旧数据；</li>
 *   <li><b>DISMISSED 已忽略</b>：误报 / 无需处理——不奖励。</li>
 * </ul>
 * 「采纳」独立于「已处理」是 V2 核心决策：用户的奖励资格取决于"上报是否真的被
 * 采用"而非"管理员有没有点过处理按钮"——二者合并会奖励低质/无效上报（根因与
 * 决策记录见 docs/积分系统-需求设计-V2-2026-08-10.md）。
 */
public enum ReportStatus {
    PENDING("待处理"),
    ADOPTED("已采纳"),
    ADOPTED_NO_REWARD("已采纳·未奖励"),
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
