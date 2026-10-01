package org.quwuting.quwutingservice.spend;

import java.math.BigDecimal;

/**
 * 账目字段的存储约束（2026-10-01，唯一声明处）。
 * <p>
 * 根因：同步接口的校验此前只覆盖「金额 > 0 / ts > 0 / clientEntryId ≤ 32」，而真正的约束在
 * DDL 里（{@code amount decimal(10,2)}、{@code venue_name varchar(100)}、{@code source_ref_id
 * varchar(32)}）。校验比存储宽 ⇒ 越界条目通过校验、在落库时抛异常 ⇒ 整批（≤200 条）事务回滚、
 * 返回 500；客户端「本地为源」按原样重放同一批 ⇒ 该设备之后的账目<b>永远上不了云</b>（毒丸），
 * 而本地显示一切正常。
 * <p>
 * 现在：校验与实体列定义都引用本类常量（一处声明），{@code SpendEntryLimitsMirrorTest} 把它们与
 * {@code V16__spend_entries.sql} 的 DDL 逐项比对；客户端协议常量（{@code constants/spendWire.ts}
 * 的 {@code SPEND_AMOUNT_MAX}）由小程序 {@code check:protocol} 门禁与本类比对。越界条目一律逐条
 * 判非法（rejectedIds），绝不抛异常拖垮整批。
 */
public final class SpendEntryLimits {

    /** amount 列精度：decimal(10,2) */
    public static final int AMOUNT_PRECISION = 10;
    public static final int AMOUNT_SCALE = 2;

    /** 金额上限（= decimal(10,2) 可表示的最大值 99,999,999.99） */
    public static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999.99");

    /** client_entry_id varchar(32) */
    public static final int CLIENT_ENTRY_ID_MAX_LENGTH = 32;

    /** source_ref_id varchar(32) */
    public static final int SOURCE_REF_ID_MAX_LENGTH = 32;

    /** venue_name varchar(100) */
    public static final int VENUE_NAME_MAX_LENGTH = 100;

    private SpendEntryLimits() {
    }
}
