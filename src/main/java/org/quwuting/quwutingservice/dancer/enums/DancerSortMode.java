package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 舞伴公开列表排序模式（2026-08-26 晚新增，见 09-dancer-and-points.md「列表排序」）。
 * <ul>
 *   <li>{@link #HOT} 热门（默认）：组合分 = 近7天认可数 + 新鲜度加成（新舞伴 14 天
 *       +2 / 近 3 天更新过相册或联系方式 +2），同分按近 30 天收藏数，再按 id 倒序兜底</li>
 *   <li>{@link #LATEST} 最新：id 倒序（新资料在前，冷启动曝光通道——新舞伴不受
 *       认可数为 0 的沉底约束，见 DancerRepository#findPublicPage）</li>
 * </ul>
 * 解析非法值抛 1001（同 DancerServiceCategory.parse 模式）；null/空串由调用方
 * 兜底 HOT（旧客户端不传 sort 零回归）。
 */
public enum DancerSortMode {

    /** 热门（默认）：认可数主导 + 新鲜度加成 + 近30天收藏 tie-break */
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
