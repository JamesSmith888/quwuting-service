package org.quwuting.quwutingservice.spend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端「单用户计时/记账流水」响应（2026-09-14，
 * GET /admin/spend/users/{userId}/entries；仅 ADMIN）。
 * admin-web 用户详情页「计时 · 账本」卡片数据源：summary = 全量历史汇总
 * （不受流水条数上限影响），entries = 按业务时刻降序的最近流水（只回未软删）。
 * <p>
 * <b>双口径（2026-10-07）</b>：summary 的<b>计数字段含软删</b>、<b>金额字段仅未删</b>；
 * entries 是<b>明细读取</b>（「账上现在有什么」），故仍只回未软删。两者口径不同是
 * 设计意图——因此 {@code summary.entryCount} 可能大于 {@code entries.size()}，
 * 差额由 {@link Summary#retractedEntries} 给出，<b>禁把该差额当作"已截断"</b>
 * （前端曾据此恒显「仅显示最近 N 笔」，见 admin-web UserDetailView#spendTruncated）。
 */
public record AdminSpendUserEntriesResponse(
        /** 全量汇总 */
        Summary summary,
        /** 最近流水（最新在前） */
        List<EntryItem> entries
) {

    /** 单用户账目汇总行（计数字段含软删、金额字段仅未删） */
    public record Summary(
            /** 账目总笔数（<b>含软删</b>——使用事实不被数据撤回改写） */
            long entryCount,
            /** 计时结算笔数（source=DANCE，含软删） */
            long danceEntries,
            /** 手动补记笔数（source=MANUAL，含软删） */
            long manualEntries,
            /** 支出总金额（EXPENSE，元；仅未删） */
            BigDecimal expenseTotal,
            /** 收入总金额（INCOME，元；仅未删） */
            BigDecimal incomeTotal,
            /**
             * 已撤回（软删）笔数 = {@code entryCount} − 当前账面笔数。
             * <p>存在的理由：让消费方能区分「删过账」与「明细被截断」两种
             * 会让 {@code entryCount > entries.size()} 的成因。
             */
            long retractedEntries
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
