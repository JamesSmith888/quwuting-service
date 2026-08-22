package org.quwuting.quwutingservice.dancer.dto.response;

import java.util.List;

/**
 * 舞伴统计响应体（GET /dancers/{id}/stats，2026-08-14 舞伴统计图第一期，
 * 2026-08-21 追加解锁信息统计，2026-08-22 追加累计指标 totals）。
 * <p>
 * 六组近30天每日时间序列（含今日，骨架 31 天，服务端补零），供前端舞伴统计页
 * 六张趋势图渲染（认可/收藏/礼物价值/分享/浏览 + 浏览来源）+ 一组「用户解锁信息」
 * 分类聚合（unlockStats，前端横向条形图）+ 一组全量历史累计指标（totals，
 * 前端「累计数据」汇总卡：总收藏数/总浏览数等常见指标）。与门店
 * {@code VenueHeatResponse} 的趋势字段同模式（countDailyTrends 骨架 + 实时口径），
 * 但只含时间序列与累计指标、不含热度指数——舞伴域暂无热度公式（第一期范围，见
 * 前端 docs/agents/09「舞伴统计」）。
 */
public record DancerStatsResponse(
        /**
         * 近30天每日认可数趋势（认可趋势图用，date + count）。
         * 数据源 = qwt_dancer_recognitions（每日一记 UNIQUE(user, dancer, date)，
         * 按 recognition_date 分组；取消=物理删除，计数即"当日有效认可数"）。
         */
        List<DancerTrendPoint> recognitionTrend,
        /**
         * 近30天每日新增收藏趋势（收藏趋势图用）。
         * 数据源 = qwt_dancer_favorites.created_at（软删，deleted=false 过滤；
         * 舞伴收藏无 unfavorited_at，故只有"新增"单序列，无门店式"取消"双线）。
         */
        List<DancerTrendPoint> favoriteTrend,
        /**
         * 近30天每日收到礼物价值趋势（礼物价值趋势图用）。
         * 数据源 = qwt_points_transactions（target_type='DANCER' AND delta&lt;0，
         * 按日 SUM(-delta)——与门店 pointsTrend 同口径，礼物价值=积分价值）。
         */
        List<DancerTrendPoint> pointsTrend,
        /**
         * 近30天每日分享次数趋势（分享趋势图用）。
         * 数据源 = qwt_dancer_shares（event_type='SHARE' 主动分享事件，不含 OPEN
         * 回流——分享趋势表达"被传播度"；OPEN 归因另见分享统计）。
         */
        List<DancerTrendPoint> shareTrend,
        /**
         * 近30天每日浏览数趋势（浏览趋势图用，含匿名，与 viewSourceTrend 同源
         * 同口径：list + share + search + other = viewTrend 恒成立）。
         * 数据源 = qwt_dancer_views（V29，按日按来源去重；匿名不参与去重）。
         */
        List<DancerTrendPoint> viewTrend,
        /**
         * 近30天每日浏览来源趋势（「浏览来源」三折线图用，date + list/share/search
         * 分列；other 前端不入图）。语义与门店 ViewSource 一致：LIST=列表页进入、
         * SHARE=分享卡片打开、SEARCH=搜索结果进入、OTHER=其他/历史兜底。
         */
        List<DancerViewSourceTrendPoint> viewSourceTrend,
        /**
         * 用户解锁信息分类聚合（2026-08-21 追加，「解锁信息」横向条形图用）。
         * 按内容类型（照片/联系方式，可扩展）聚合 qwt_points_unlocks 累计解锁
         * 人次/人数 + 当前门槛积分；非时间序列（解锁低频离散事件，无趋势语义）。
         * 排序 = 解锁人次降序（无记录的类别不上行）。
         */
        List<DancerUnlockStat> unlockStats,
        /**
         * 全量历史累计指标（2026-08-22 追加，「累计数据」汇总卡用——总收藏数 /
         * 总浏览数等常见指标一览）。与各趋势序列同源同口径，仅窗口不同（累计=全量，
         * 趋势=近30天）；浏览/认可/收藏/分享/礼物价值五类，见 {@link DancerTotals}。
         */
        DancerTotals totals,
        /**
         * 滚动窗口统计口径的截止日期（yyyy-MM-dd，实时口径 = 今天）。
         * 所有趋势序列统计到请求时刻（含今日已发生的数据），同一天内多次请求结果
         * 随请求时刻漂移——实时口径的必然代价，前端 banner「数据实时更新 · 含今日」
         * 显性承担口径说明（对齐门店 2026-08-13 实时化决策）。
         */
        String statsAsOfDate
) {
}
