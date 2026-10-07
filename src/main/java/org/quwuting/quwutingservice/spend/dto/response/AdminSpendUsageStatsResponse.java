package org.quwuting.quwutingservice.spend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理端「计时器 & 计时账本使用情况」统计响应（2026-09-14，
 * GET /admin/spend/usage-stats；仅 ADMIN）。admin-web 数据看板
 * 「计时 · 账本使用」面板数据源，一次往返返回全部序列。
 * <p>
 * 口径权威 = {@code SpendStatsRepository} + {@code SpendStatsSql} 类注释
 * （docs/agents/35-dashboard-stats.md）：用户范围剔除 ADMIN 运营号 / test_ 开发联调号 /
 * 微信审核账号（wechat_review=true）。计时结算 = source='DANCE'（计时器结算自动入账）；
 * 手动补记 = source='MANUAL'。
 * <p>
 * <b>双口径（2026-10-07）</b>：<b>计数类含软删行</b>（用户撤回数据撤不回使用行为）、
 * <b>金额类仅未删行</b>（撤回的金额不算消费）。差异由 {@link Summary#retractedEntries}
 * 显式暴露，便于解释"笔数比明细多"。单用户明细（{@code /admin/spend/users/{id}/entries}）
 * 仍是账面口径（只回未软删），它回答的是"账上现在有什么"而非"用过没有"。
 */
public record AdminSpendUsageStatsResponse(
        /** 累计汇总（全量历史） */
        Summary summary,
        /** 近 N 天按日序列（含今日，骨架补零） */
        List<DailyPoint> daily,
        /** 支出分类分布（EXPENSE 口径，只含有数据行，前端按固定 7 类补零） */
        List<CategorySlice> byCategory,
        /** 关联门店 TOP（按账目条数降序） */
        List<VenueSlice> byVenue
) {

    /** 累计汇总行（计数走事实口径含软删、金额走账面口径仅未删） */
    public record Summary(
            /** 有账目的去重用户数（记账用户；含账目已全部被撤回的用户） */
            long totalUsers,
            /** 有 ≥1 条计时结算账目的用户数（用过计时器结算） */
            long timerUsers,
            /** 近 7 日（含今日）有账目的去重用户数 */
            long active7d,
            /** 账目总条数（<b>含软删</b>——使用事实不被数据撤回改写） */
            long totalEntries,
            /** 计时结算条目数（≈ 计时场次，含收入向结算；含软删） */
            long danceEntries,
            /** 手动补记条目数（含软删） */
            long manualEntries,
            /** 支出总金额（EXPENSE，元；<b>仅未删</b>——账面口径） */
            BigDecimal expenseTotal,
            /** 收入总金额（INCOME，元；仅未删） */
            BigDecimal incomeTotal,
            /**
             * 已撤回（软删）条目数 = {@code totalEntries} − 当前账面条目数。
             * <p>暴露它是为了让「计数值 ≥ 明细条数」这件事<b>可解释</b>：
             * 运营看到用户删过账时，不必怀疑数据出错。
             */
            long retractedEntries
    ) {
    }

    /** 单日使用点 */
    public record DailyPoint(
            /** 统计日（Asia/Shanghai 自然日） */
            LocalDate day,
            /** 当日计时结算条目数 */
            long danceEntries,
            /** 当日手动补记条目数 */
            long manualEntries,
            /** 当日活跃记账用户数（去重） */
            long activeUsers
    ) {
    }

    /** 支出分类切片（笔数含软删、金额仅未删） */
    public record CategorySlice(
            /** 分类（SpendCategory 枚举名） */
            String category,
            /** 条目数（含软删） */
            long entryCount,
            /** 金额合计（元，仅未删；该分类条目全部被撤回时为 0 且行仍出现） */
            BigDecimal total
    ) {
    }

    /** 关联门店切片（笔数含软删、金额仅未删） */
    public record VenueSlice(
            /** 门店 id（可空 = 未关联，不入本切片） */
            Long venueId,
            /** 门店名称（账目行快照） */
            String venueName,
            /** 关联账目条数（含软删——用户撤回后仍算"在此店记过账"） */
            long entryCount,
            /** 金额合计（元，仅未删） */
            BigDecimal total
    ) {
    }
}
