package org.quwuting.quwutingservice.dancer.enums;

/**
 * 舞伴信息核验管理操作（PUT /admin/dancers/{id}/verification，仅 ADMIN）。
 * <ul>
 *   <li>{@link #VERIFY}：授予认证——UNVERIFIED / PENDING_REVIEW → VERIFIED
 *       （PENDING_REVIEW 场景 = 复核后确认恢复）；</li>
 *   <li>{@link #UNVERIFY}：撤销认证——VERIFIED / PENDING_REVIEW → UNVERIFIED，
 *       <b>reason 必填</b>（撤销必须留痕理由，随站内信通知舞伴）。</li>
 * </ul>
 */
public enum DancerVerificationAction {
    /** 授予 / 复核确认（→ VERIFIED） */
    VERIFY,
    /** 撤销（→ UNVERIFIED；reason 必填） */
    UNVERIFY
}
