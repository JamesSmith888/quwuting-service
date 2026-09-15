package org.quwuting.quwutingservice.venueactivity.enums;

import lombok.Getter;

/**
 * 外层调度：活动**有效期**形态（决定"何时自动下线"）。
 * <p>
 * 这一层之所以做成枚举 + 策略类（而内层生效窗口是纯数据），判据有两条：
 * <ol>
 *   <li><b>有真实的"新增形态"预期</b>——每月固定日、仅节假日、仅开业当天等，
 *       都是新算法而不是新取值，扩展点必须存在；</li>
 *   <li><b>它驱动状态机</b>——自动下线必须显式回答"何时结束"，
 *       不允许用"字段是不是 null"去猜（null 语义会随业务演进被悄悄改掉）。</li>
 * </ol>
 * <p>
 * ⚠️ <b>「单日」故意不设枚举值</b>：它是 {@link #DATE_RANGE} 在
 * {@code start_date = end_date} 时的退化情形，同一套算法换了个说法而已。
 * admin 端提供"单日"快捷输入，但落库仍是 DATE_RANGE——避免同一算法两个名字
 * （判据：枚举值必须对应不同**算法**，不能只对应不同**输入方式**）。
 */
@Getter
public enum ActivityOuterSchedule {

    /** 长期有效（无起止日期），不参与自动下线 */
    ALWAYS("长期有效"),

    /** 日期区间（start_date ~ end_date 闭区间；start = end 即单日） */
    DATE_RANGE("指定日期");

    private final String displayName;

    ActivityOuterSchedule(String displayName) {
        this.displayName = displayName;
    }
}
