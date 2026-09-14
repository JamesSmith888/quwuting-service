package org.quwuting.quwutingservice.spend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「单用户计时/记账流水」响应（2026-09-14，
 * GET /admin/spend/users/{userId}/entries；仅 ADMIN）。
 * admin-web 用户详情页「计时 · 账本」卡片数据源：summary = 全量历史汇总
 * （不受流水条数上限影响），entries = 按业务时刻降序的最近流水（只回未软删，
 * 与统计口径一致）。
 */
public record AdminSpendUserEntriesResponse(
        /** 全量汇总 */
        Summary summary,
        /** 最近流水（最新在前） */
        List<EntryItem> entries
) {

    /** 单用户账目汇总行 */
    public record Summary(
            /** 账目总笔数（未软删） */
            long entryCount,
            /** 计时结算笔数（source=DANCE） */
            long danceEntries,
            /** 手动补记笔数（source=MANUAL） */
            long manualEntries,
            /** 支出总金额（EXPENSE，元） */
            BigDecimal expenseTotal,
            /** 收入总金额（INCOME，元） */
            BigDecimal incomeTotal
    ) {
    }

    /** 单笔账目 */
    public record EntryItem(
            /** 行 id */
            long id,
            /** 业务发生时刻（结算=停止时刻；手动=记账时刻） */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime ts,
            /** 金额（元，恒正；方向由 direction 决定展示） */
            BigDecimal amount,
            /** 方向（EXPENSE / INCOME） */
            String direction,
            /** 来源（DANCE=计时结算 / MANUAL=手动补记） */
            String source,
            /** 消费分类（SpendCategory 枚举名） */
            String category,
            /** 关联门店 id（可空 = 未关联） */
            Long venueId,
            /** 门店名称快照（可空 = 未关联） */
            String venueName,
            /** 结算时长秒数（仅 DANCE 来源；手动为 null） */
            Integer durationSeconds
    ) {
    }
}
