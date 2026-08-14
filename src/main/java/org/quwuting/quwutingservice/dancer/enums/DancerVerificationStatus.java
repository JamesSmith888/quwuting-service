package org.quwuting.quwutingservice.dancer.enums;

/**
 * 舞伴信息核验状态（2026-08-14 官方认证——"信息已核验"标识）。
 * <p>
 * 治理语义（与 DancerStatus「先认证、后展示」的隐私边界互补，见 AGENTS.md
 * 「舞伴官方认证」）：认证 = <b>身份与公开信息经平台人工核验属实</b>的信息真实性
 * 背书（裁决事实，不裁决人品）——不是"推荐/靠谱/优质"，文案与展示必须锁定
 * 「信息已核验」语义。
 * <p>
 * 认证是可回退的信任背书（状态机）：
 * <ul>
 *   <li>{@link #UNVERIFIED}：未认证（默认）。撤销后的终态；</li>
 *   <li>{@link #VERIFIED}：已认证——admin 授予（人工核验通过）；</li>
 *   <li>{@link #PENDING_REVIEW}：待复核——舞伴本人编辑已认证资料后自动降级
 *       （防认证挂在过期信息上；曾认证被撤销后再次编辑同样触发，形成
 *       "撤销 → 修改资料 → 重新核验"闭环）。对外语义 = 未认证（徽标不再展示）。</li>
 * </ul>
 * 全部状态变迁留痕于 qwt_dancer_verification_logs（谁、何时、理由）；
 * 撤销必须填写原因（被撤销舞伴可见，延续"被指涉方有申辩权"治理原则）。
 */
public enum DancerVerificationStatus {
    /** 未认证（默认态；撤销后的终态） */
    UNVERIFIED,
    /** 已认证（admin 人工核验通过，公开展示「信息已核验」徽标） */
    VERIFIED,
    /** 待复核（舞伴本人编辑触发；admin 复核后确认 VERIFIED 或撤销回 UNVERIFIED） */
    PENDING_REVIEW
}
