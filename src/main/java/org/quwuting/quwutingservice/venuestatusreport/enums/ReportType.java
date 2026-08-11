package org.quwuting.quwutingservice.venuestatusreport.enums;

/**
 * 突发事件（紧急公告）类型——实时信号层的事件维度，2026-08-11 泛化替代原 {@code ReportReason}。
 * <p>
 * 详情页「紧急公告」区域展示的门店突发事件 8 类枚举（用户确认清单）。每类携带
 * 展示文案 / 严重级（前端色阶）/ 是否影响营业状态（采纳联动）/ TTL 小时数
 * （expires_at 写入时 = createdAt + 本值）。
 * <p>
 * 语义边界：
 * <ul>
 *   <li><b>状态类</b>（{@code affectsStatus=true}）：SUSPENDED / RESUMED——采纳时联动
 *       门店营业状态（SUSPENDED→暂停营业、RESUMED→营业中），其余类型为纯信息信号，
 *       采纳不改状态；</li>
 *   <li><b>提交守卫</b>：SUSPENDED 仅对声称营业（OPEN）门店有意义（非营业 1010 拒绝）；
 *       RESUMED 仅对声称非营业门店有意义（营业中 1012 拒绝）；事件类不受存储态约束；</li>
 *   <li><b>奖励边界</b>：SITUATION_UNCLEAR（情况不明）信息量最低、噪音高危，采纳不设
 *       积分奖励，其余类型采纳即奖（管理员把关防刷）。</li>
 * </ul>
 * 新增类型 = 追加枚举值 + 前端 types/venue.ts 同步（label/severity 双端镜像，
 * 展示文案以本枚举 displayName 为权威源）。
 */
public enum ReportType {

    /** 突然检查（临检/整顿）——高严重、纯事件 */
    SUDDEN_INSPECTION("突然检查", Severity.HIGH, false, 6),
    /** 情况不明——低严重、噪音高危（提交必须附补充说明，采纳不奖励） */
    SITUATION_UNCLEAR("情况不明", Severity.LOW, false, 2),
    /** 暂停营业——状态类，采纳联动门店 → SUSPENDED（沿用原暂停报采纳流） */
    SUSPENDED("暂停营业", Severity.MEDIUM, true, 4),
    /** 舞池不开——部分限流、纯事件 */
    DANCE_FLOOR_CLOSED("舞池不开", Severity.MEDIUM, false, 6),
    /** 突然清场——高严重、纯事件 */
    SUDDEN_EVICTION("突然清场", Severity.HIGH, false, 6),
    /** 恢复营业——状态类，采纳联动门店 → OPEN，同时承担"解除信号"角色 */
    RESUMED("恢复营业", Severity.RECOVERY, true, 24),
    /** 突然关门——高严重、纯事件（CLOSED 存储态语义为"休息中"，不联动） */
    SUDDEN_CLOSURE("突然关门", Severity.HIGH, false, 6),
    /** 禁龙——限制性规定、纯事件 */
    NO_PARTNER_DANCE("禁龙", Severity.MEDIUM, false, 6);

    private final String displayName;
    private final Severity severity;
    private final boolean affectsStatus;
    private final int ttlHours;

    ReportType(String displayName, Severity severity, boolean affectsStatus, int ttlHours) {
        this.displayName = displayName;
        this.severity = severity;
        this.affectsStatus = affectsStatus;
        this.ttlHours = ttlHours;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Severity getSeverity() {
        return severity;
    }

    /** 采纳时是否联动门店营业状态（状态类） */
    public boolean isAffectsStatus() {
        return affectsStatus;
    }

    /** 该类型的信号 TTL（小时），expires_at 写入时使用 */
    public int getTtlHours() {
        return ttlHours;
    }

    /**
     * 严重级（前端色阶：红=强制事件 / 橙=警示 / 黄=弱信息 / 绿=恢复）。
     * 前端展示直接消费本枚举值，禁止前端自行映射。
     */
    public enum Severity {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        RECOVERY("recovery");

        private final String code;

        Severity(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
