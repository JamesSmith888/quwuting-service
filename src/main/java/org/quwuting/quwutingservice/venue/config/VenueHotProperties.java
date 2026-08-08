package org.quwuting.quwutingservice.venue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「热门场所」标记配置。
 * <p>
 * 配置键：{@code venue.hot.min-heat-score}（YAML）。
 * <p>
 * {@code minHeatScore} 是热门标记的<b>绝对行为热度门槛</b>（2026-08-08 确立，见后端
 * AGENTS.md「热门场所标记」章节）：热门的判定 = 城市内相对排名（top 20%）<b>且</b>
 * <b>行为热度</b>（完整热度分扣除运营权重 sortWeight）≥ 本门槛。相对排名解决跨城市
 * 基数差异（上海普通场所的收藏量可能 &gt; 小城市最热门场所），绝对门槛排除"小池塘里
 * 最不冷"的伪热门——没有实质用户活跃的场所（如仅 2 次浏览）即使同城市排名第一也
 * 不得标记热门。
 * <p>
 * <b>门槛作用于行为热度部分（2026-08-08 用户反馈根因修复）</b>：sortWeight 是运营
 * 权重，仍参与城市内排名（top 20%）与列表排序（运营推广提升曝光属其本职），但
 * <b>不得伪造热门资格</b>——历史实现把门槛放在含 sortWeight 的完整分上，运营加权
 * 门店（如 sortWeight=68）即使行为热度仅 2（近30天 2 次浏览）也被抬过门槛，出现
 * "详情页热度指数 2 却有热门标签"的自相矛盾（生产实证：南充市 venue 90，sortWeight
 * 20 + 行为 2 = 22 ≥ 20 命中热门）。门槛移到行为部分后：热门 ⟺ 行为热度 ≥ 门槛，
 * 与详情页热度 chip 的核心行为项口径一致（满意度偏移属评分纠偏小项，不参与热门
 * 判定，见 AGENTS.md「热门场所标记」演进说明）。
 * <p>
 * 默认 70 的语义：≈ 近30天内 7 次收藏、或 70 次浏览、或 14 条动态、或 1 收藏 +
 * 60 浏览等组合——低于该值视为"无实质活跃信号"（纯浏览/冷启动）。配置可经
 * application.yaml 调整，无需改代码（单实例部署，改动即时生效）。
 */
@ConfigurationProperties(prefix = "venue.hot")
public record VenueHotProperties(int minHeatScore) {

    /** 配置缺失/非法时的安全回退：70 分（≈ 近30天 7 次收藏或 70 次浏览的最低活跃门槛） */
    private static final VenueHotProperties DEFAULT = new VenueHotProperties(70);

    public VenueHotProperties {
        if (minHeatScore <= 0) {
            minHeatScore = DEFAULT.minHeatScore();
        }
    }

    public int minHeatScore() {
        return minHeatScore;
    }
}
