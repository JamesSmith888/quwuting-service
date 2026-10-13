package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 消费总览（GET /spend/overview?month=yyyy-MM&direction=EXPENSE|INCOME）：一次往返
 * 返回统计页全部数据——移动端弱网下禁 4 个端点 4 次往返（44 号文档 §12.5；ledger-server
 * 的 by-category/trend 分端点是桌面思维，不照搬）。
 * <p>
 * 聚合全部服务端 SQL 算（GROUP BY），禁拉明细前端算；`direction`（2026-10-13 起，
 * 缺省 EXPENSE）决定整份统计的口径方向——客人看支出、舞伴看收入。缺类补零按
 * 该方向的合法分类集（SpendCategory.forDirection）由服务端负责（分类集枚举权威），
 * 前端零补零逻辑。
 */
public record SpendOverviewResponse(
        Summary summary,
        /** 该方向合法分类集全量（含 0 值；顺序 = SpendCategory.forDirection），环形图按此渲染 */
        List<CategorySlice> byCategory,
        /** 门店 TOP（金额降序，≤5；venueName 为落账时快照） */
        List<VenueSlice> byVenueTop,
        /** 未关联桶独立返回（禁并入 OTHER——数据缺口可见，§13.4） */
        BigDecimal unlinkedTotal,
        /** 近 6 个月趋势（含请求月，无数据月补零，月升序） */
        List<MonthPoint> trendMonths
) {

    /** 月度汇总：场次只数 DANCE 条目（口径与客户端 summarizeMonth 一致，禁漂移） */
    public record Summary(
            BigDecimal total,
            long entryCount,
            long sessionCount,
            /** 场均 = total / sessionCount（sessionCount=0 时为 0，禁除零） */
            BigDecimal avgPerSession
    ) {
    }

    public record CategorySlice(
            String category,
            BigDecimal total,
            long entryCount
    ) {
    }

    public record VenueSlice(
            Long venueId,
            String venueName,
            BigDecimal total
    ) {
    }

    public record MonthPoint(
            /** "yyyy-MM" */
            String month,
            BigDecimal total
    ) {
    }
}
