package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 消费总览（GET /spend/overview?month=yyyy-MM）：一次往返返回统计页全部数据——
 * 移动端弱网下禁 4 个端点 4 次往返（44 号文档 §12.5；ledger-server 的
 * by-category/trend 分端点是桌面思维，不照搬）。
 * <p>
 * 聚合全部服务端 SQL 算（GROUP BY），禁拉明细前端算；固定 6 类缺类补零由服务端
 * 负责（分类集枚举权威），前端零补零逻辑。
 */
public record SpendOverviewResponse(
        Summary summary,
        /** 固定 6 类全量返回（含 0 值类），环形图按此渲染永不超 6 扇区 */
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
