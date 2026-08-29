package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴「排名热度」响应体（GET /dancers/{id}/stats 的 heat 字段，2026-08-29 追加）。
 * <p>
 * <b>口径</b>：与列表 HOT 排序公式完全同源（权重唯一事实源 =
 * {@code DancerHeatWeights}，输入计数器 = DancerStatsRepository#countDancerHeatCounters）——
 * 对齐门店「列表排序与热度页统一」2026-08-08 先例，根治「排序规则对用户不可见、
 * 不可问责」的口径漂移土壤。
 * <p>
 * <b>文案契约</b>：formulaText（中等简洁人话版）与 formulaDetail（完整条目化规则）
 * 由后端生成下发、前端只渲染——权重调整后展示自动跟随，禁止前端硬编码权重
 * （与 VenueHeatResponse.formulaText/formulaDetail 同契约，2026-08-27 门店先例）。
 */
public record DancerHeatResponse(
        /** 排名热度分（列表 HOT 排序主键值：解锁×3 + 认可×1 + 新鲜度加成） */
        long heatScore,
        /** 近7天联系方式解锁数（主导信号，权重 3） */
        long unlockContact7d,
        /** 近30天联系方式解锁数（tie-break 一级，展示辅助） */
        long unlockContact30d,
        /** 近7天认可数（平滑项，权重 1） */
        long recognition7d,
        /** 近30天收藏数（tie-break 二级，展示辅助） */
        long favorite30d,
        /** 新舞伴加成（0/2：创建在 14 天保护期内 = 2） */
        int newDancerBonus,
        /** 资料新鲜度加成（0/2：近 3 天更新过相册或联系方式任一 = 2） */
        int freshUpdateBonus,
        /**
         * 热度规则简述（中等简洁人话版，前端默认展示——如
         * "热度 14 = 近7天联系解锁 1×3 · 认可 12×1 · 新舞伴 +2"）
         */
        String formulaText,
        /** 热度规则详情（「查看完整计算规则」点击展开——含信号原则说明） */
        String formulaDetail
) {
}
