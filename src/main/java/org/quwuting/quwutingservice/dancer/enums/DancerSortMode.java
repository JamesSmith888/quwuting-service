package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 舞伴公开列表排序模式（2026-08-26 晚新增，见 09-dancer-and-points.md「列表排序」；
 * 2026-08-29 排序 v2——付费意向主导，权重唯一事实源 = DancerHeatWeights）。
 * <ul>
 *   <li>{@link #HOT} 热门（默认）：排名热度 = 近7天联系解锁数×3（付费意向主导，
 *       2026-08-29 新增）+ 近7天认可数×1（平滑项）+ 新鲜度加成（新舞伴 14 天
 *       +2 / 近 3 天更新过相册或联系方式 +2）；tie-break 依次 = 近30天联系解锁数、
 *       近30天收藏数，再按 id 倒序兜底</li>
 *   <li>{@link #LATEST} 最新：id 倒序（新资料在前，冷启动曝光通道——新舞伴不受
 *       认可数为 0 的沉底约束，见 DancerRepository#findPublicPage）</li>
 * </ul>
 * 解析非法值抛 1001（同 DancerServiceCategory.parse 模式）；null/空串由调用方
 * 兜底 HOT（旧客户端不传 sort 零回归）。
 */
public enum DancerSortMode {

    /** 热门（默认）：付费意向主导 + 认可平滑 + 新鲜度加成（详见类注释与 DancerHeatWeights） */
    HOT,
    /** 最新：id 倒序 */
    LATEST;

    /** 解析排序模式（非法值 → 1001「无效的排序模式」） */
    public static DancerSortMode parse(String code) {
        try {
            return DancerSortMode.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(1001, "无效的排序模式");
        }
    }
}
