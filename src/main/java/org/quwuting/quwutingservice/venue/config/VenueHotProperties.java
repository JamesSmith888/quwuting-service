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
 * 默认 70 的语义（<b>2026-08-27 浏览贡献重构后校准；2026-09-07 收藏权重 15→8 复校准；
 * <b>2026-09-19 浏览改人数口径后再次复校准</b>）：行为热度的浏览项现为「近30天去重浏览
 * 人数×0.3 + 近7天去重浏览人数×0.4 + ln(1+匿名行数)×1」（见 VenueHeatWeights）。
 * <b>2026-09-19 真库实测：热门集合 4 家 → 5 家</b>（浏览项由封顶 6.4 升至 ≈59 分，
 * 高人流量门店（抖舞 119 UV / 丽莎 112 UV）现在可以靠"有多少人来看"部分达标）。
 * 收藏权重 ×8 不变，70 ≈ 近30天 9 次收藏、或 6 次收藏 + 2 次评分 + 2 条正向反馈，
 * <b>亦可 = 大量浏览 + 少量主动信号</b>（如 100 UV 30 分 + 5 收藏 40 分）——这是本次
 * 改动的预期效果：<b>"光有人看"重新成为热门的一部分，但不再是唯一路径</b>
 * （匿名项被 ln 封顶 ≈6 分，刷量仍无法买热门）。
 * 配置可经 application.yaml 调整，无需改代码（单实例部署，改动即时生效）；
 * 上线初期若热门数量过少（数据稀疏），运营可下调（如 40）观察。
 */
@ConfigurationProperties(prefix = "venue.hot")
public record VenueHotProperties(int minHeatScore) {

    /** 配置缺失/非法时的安全回退：70 分（≈ 近30天 9 次收藏 ×8，或主动信号组合，或「大量浏览 + 少量主动信号」） */
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
