package org.quwuting.quwutingservice.venuereaction;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * Reaction 热度统计时间窗口（列表页 Top 徽标的排序/筛选窗口）。
 * <p>
 * 2026-08 确立：列表页默认「近7天」——舞厅属强时间变化场景，近 7 天窗口比近 30 天更能反映
 * "现在值不值得去"（见需求「数据统计规则」与 AGENTS.md「Reaction 快速反馈系统」）。
 * 前端时间筛选（近7天/近30天/全部）切换时重新请求列表，服务端按所选窗口返回 Top 徽标。
 * <p>
 * 注意：本枚举只控制"徽标排序/筛选/展示"的窗口，与 {@code ReactionStat} 四窗口统计
 * （countToday/count7d/count30d/countAll 全量下发）无关——详情页永远拿到全量四窗口。
 */
public enum ReactionWindow {
    DAYS_7("7d"),
    DAYS_30("30d"),
    ALL("all");

    private final String code;

    ReactionWindow(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 解析请求参数（"7d"/"30d"/"all"）。null/空白 → 默认 DAYS_7（需求默认展示近7天）；
     * 非法值抛业务异常（与 ReactionCode.isValid 的防御风格一致，400 而非静默降级）。
     */
    public static ReactionWindow from(String code) {
        if (code == null || code.isBlank()) {
            return DAYS_7;
        }
        for (ReactionWindow window : values()) {
            if (window.code.equals(code)) {
                return window;
            }
        }
        throw new BusinessException(1007, "无效的 Reaction 统计窗口");
    }
}
