package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场所热度响应体（GET /venues/{id}/heat）。
 * <p>
 * 综合浏览量、收藏、评价、营业稳定性等多维度统计。
 * 权重公式与公式文案（formulaText/formulaDetail）收敛在 VenueHeatService 内部——
 * 前端直接渲染公式文案，禁止硬编码权重（2026-08 确立，消灭"权重调整后展示失真"）。
 */
public record VenueHeatResponse(
        /** 综合热度指数（加权公式见 VenueHeatService） */
        long heatScore,

        // ── 浏览 ──
        /** 近30天浏览量（含匿名，UV+PV 混合口径） */
        long viewCount30d,
        /** 近30天独立用户浏览数（仅已登录用户去重 UV） */
        long viewUv30d,

        // ── 收藏 ──
        /**
         * 收藏总数（累计，页面展示用——2026-09-01 起<b>不再参与热度公式</b>；
         * 公式收藏项唯一输入 = 近30天新增收藏 newFavoriteCount30d，见 VenueHeatWeights。
         * 根因：旧双列（总数×10 + 新增×15）是集合包含关系，一次收藏重复计 25 分。）
         */
        long favoriteCount,
        /** 近30天新增收藏 */
        long newFavoriteCount30d,
        /**
         * 近30天每日新增收藏趋势（收藏趋势图「新增收藏」折线用），按日期升序，无收藏的日期已补零。
         * 窗口与其余滚动指标一致为 30 天（2026-08-08 由 14 天扩展——时间范围刷选
         * 控件需要足够长的全量窗口才能"缩放"，见 AGENTS.md「时间范围刷选控件」）；
         * 2026-08-13 实时化后含今日（骨架 31 天，与 viewTrend 等序列同构）。
         * 注意：本序列是"近30天新增"窗口口径，与 favoriteCount（全量历史累计）不同——
         * 顶部收藏数 > 0 但近30天无新增收藏时本序列恒 0，属正常口径差异（前端空图
         * 恒渲染 + 提示行承接，见前端 AGENTS.md「小程序内联图表渲染规范」）。
         */
        List<FavoriteTrendPoint> favoriteTrend,
        /**
         * 近30天每日取消收藏趋势（收藏趋势图「取消收藏」折线用，2026-08-13 V19 新增），
         * 与 favoriteTrend 同骨架同窗口（date + count），按日期升序已补零。
         * 数据源 = qwt_favorites.unfavorited_at（取消动作时刻，FavoriteService 唯一写方）：
         * 取消收藏写入、重新收藏清空——本序列计"取消动作"，与「新增收藏」并排呈现后，
         * "新增 − 取消 = 净变化"可被趋势图验证（顶部收藏总数 = 历史新增 − 历史取消的
         * 理论恒等式）。历史取消动作无时间戳可回溯，数据自 V19 上线日起积累（已知局限）。
         */
        List<FavoriteTrendPoint> unfavoriteTrend,
        /**
         * 近30天每日浏览数趋势（浏览趋势图用），结构与收藏趋势一致（date + count）。
         * 浏览记录按日去重计数，含匿名（与 viewCount30d 同源同口径，含今日实时）。
         */
        List<FavoriteTrendPoint> viewTrend,
        /**
         * 近30天每日浏览来源趋势（「浏览来源」双折线图用，date + list/share/other 分列）。
         * 2026-08-13 新增：list=列表页进入、share=分享卡片打开、other=其他（含历史兜底）。
         * list + share + other = 当日 viewTrend 值（同源可交叉验证）；图上前端只画
         * list/share 两条折线。历史数据（source 列上线前）全部归 other——新图数据自
         * 版本上线日起积累，属已知局限（见前端 AGENTS.md「浏览来源统计」）。
         */
        List<ViewSourceTrendPoint> viewSourceTrend,
        /**
         * 近30天每日 Reaction 趋势（反馈趋势图用，正/负向分列），按日期升序已补零。
         * 分极性数据直接服务 2026-08 确立的「负向不计入热度」语义——负向单独呈现。
         */
        List<ReactionTrendPoint> reactionTrend,

        // ── 动态 ──
        /**
         * 动态总数（展示用；2026-09-01 起热度公式输入 = 近30天新增动态 newPostCount30d，
         * 动态为 admin 运营内容，存量项制造马太——见 VenueHeatWeights）
         */
        long postCount,
        /** 近30天新增动态 */
        long newPostCount30d,

        // ── 评价互动 ──
        /** 近30天评分数（按 created_at 窗口，改分不刷新窗口，防刷分） */
        long ratingCount30d,
        /** 近30天正向 Reaction 数（仅 Polarity.POSITIVE，热度公式计入项） */
        long positiveReactionCount30d,
        /** 近30天负向 Reaction 数（仅 Polarity.NEGATIVE，不计入公式，供详情页展示负面信号） */
        long negativeReactionCount30d,

        // ── 积分（2026-08-10 V2 新增：赠送影响排名，低权重可校准） ──
        /** 收到积分总数（target_type='VENUE' 全量） */
        long pointsReceivedTotal,
        /** 近30天收到积分（热度公式积分输入项，× app.points.heat-weight） */
        long pointsReceived30d,
        /**
         * 近30天每日收到积分趋势（「收到积分」统计图用），按日期升序已补零。
         * 与其余趋势序列同构（date + count），口径一致（含今日实时、31 天骨架）。
         */
        List<FavoriteTrendPoint> pointsTrend,
        /**
         * 收到礼物聚合（code → 件数，2026-08-12 礼物化：「收获的支持」礼物墙数据源）。
         * 展示载体与 pointsReceived*（价值，热度输入项）同源不同维。
         */
        List<org.quwuting.quwutingservice.points.dto.GiftCountResponse> giftsReceived,

        // ── 满意度 ──
        /** 综合满意度（1-10，各维度等权均分），评价人数不足时为 null */
        Double satisfactionScore,
        /** 评价总人数（去重用户） */
        long ratingTotalCount,

        // ── 营业稳定性 ──
        /** 近30天暂停营业次数（状态变更为 SUSPENDED 的次数） */
        long suspensionCount30d,
        /** 当前状态持续天数 */
        long currentStatusDays,
        /** 当前状态枚举值 */
        String currentStatus,
        /** 当前状态展示名 */
        String currentStatusDisplay,

        /**
         * 状态可信度等级（HIGH / MEDIUM / LOW），由三维判定派生：
         * 当前状态类型（营业中 vs 非营业）× 近30天暂停次数 × 状态持续天数，活跃报告 override 为 LOW。
         * 判定逻辑与文案的唯一事实源在 VenueHeatService，前端只渲染（见 statusConfidenceText / statusConfidenceRuleDetail）。
         */
        String statusConfidence,
        /**
         * 状态可信度结论文案（如「稳定营业」/「状态可信」/「建议确认」/「数据可能过时」），
         * 由后端按「等级 × 当前状态类型」生成下发——前端直接渲染，禁止前端硬编码文案
         * （与 formulaText 同模式：文案唯一事实源在后端，规则调整免发前端）。
         * 2026-08-08 根因修复：已停业门店近30天暂停 0 次被判 HIGH 后，前端硬编码
         * 「稳定营业」造成"已停业却显示稳定营业"的语义错配，文案生成由此收编到后端。
         */
        String statusConfidenceText,
        /**
         * 状态可信度判定依据文案（如「判定规则：近30天暂停 0 次 = 稳定…」），
         * 后端生成下发，营业状态详情弹窗「可信度」区块直接渲染。
         * 2026-08-29 白话化重构：一句话 = 关键事实 + 必要时的行动建议，
         * 不再复述判定规则全文（规则术语用户无法一眼理解，见 VenueHeatService）。
         */
        String statusConfidenceRuleDetail,

        /**
         * 近30天状态变更记录（最多 5 条，按变更时间倒序，2026-08-29 新增）。
         * 营业状态详情弹窗「状态记录」区块数据源——可信度判定的证据层：
         * 每条暂停/恢复/停业变迁直接支撑「近30天暂停 N 次」「状态持续天数」
         * 两个判定输入；无记录时"近30天无状态变更"本身即稳定性的证据。
         */
        List<VenueStatusLogItem> statusLogs,

        // ── 用户实时状态报告（独立信号层，不修改 Venue.status） ──
        /**
         * 活跃报告数：最近 TTL（当前 4 小时）内用户上报"暂停营业"的数量。
         * 与 suspensionCount30d（管理通道审计）不同，这是众包实时信号。
         * 当此值 > 0 时，statusConfidence 被 override 为 LOW（见 VenueHeatService）。
         */
        int activeReportCount,

        /**
         * 最新活跃报告时间（用于"X分钟前"展示）。
         * null = 无活跃报告。此时间是实时事实，不受 statsAsOfDate 窗口约束。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime latestReportTime,

        // ── 公式文案（后端生成，前端直接渲染） ──
        /** 热度规则简述（2026-08-27 起为「中等简洁人话版」，前端默认展示——如
         *  "热度 76 = 近30天人气：浏览贡献 7（来源加权+近7天翻倍）· 新增收藏 5×15 · …"） */
        String formulaText,
        /** 热度规则详情（完整条目化规则，前端「查看完整计算规则」点击展开——含浏览
         *  来源加权/压缩换算、各维度权重、满意度中性偏移与负向反馈规则） */
        String formulaDetail,

        /**
         * 滚动窗口统计口径的截止日期（yyyy-MM-dd，2026-08-13 实时化后 = 今天）。
         * 所有统计字段（除 currentStatusDays/currentStatus/activeReportCount/
         * latestReportTime 外）均统计到请求时刻（含今日已发生的数据），同一天内多次
         * 请求结果随请求时刻漂移——实时口径的必然代价，前端 banner「数据实时更新 ·
         * 含今日」显性承担口径说明（由「截至昨日」口径迁移，见后端 AGENTS.md
         * 「统计口径」章节）。
         */
        String statsAsOfDate
) {}
