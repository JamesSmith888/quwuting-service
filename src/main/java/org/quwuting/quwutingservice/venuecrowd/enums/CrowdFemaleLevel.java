package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 在店舞伴（女）数量档位（门店热度主信号，2026-08-29）。
 * <p>
 * 锚点 = 用户实测行业口径（docs/agents/27-venue-crowd-report.md「业务基准」，勿改）：
 * 冷清 0~20 个 / 一般 ≈50 / 不错 ≈100 / 火爆 300+（见过的上限 ≈450）。
 * <p>
 * 契约：{@code level} 为落库值（smallint，1-4）；{@code displayName} 与
 * {@code anchor} 为展示文案（前端零拼接，仅渲染后端权威值）。
 */
public enum CrowdFemaleLevel {
    COLD(1, "冷清", "0-20"),
    NORMAL(2, "一般", "约50"),
    GOOD(3, "不错", "约100"),
    PACKED(4, "火爆", "300+");

    private final int level;
    private final String displayName;
    private final String anchor;

    CrowdFemaleLevel(int level, String displayName, String anchor) {
        this.level = level;
        this.displayName = displayName;
        this.anchor = anchor;
    }

    public int getLevel() {
        return level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAnchor() {
        return anchor;
    }

    public static CrowdFemaleLevel of(int level) {
        for (CrowdFemaleLevel v : values()) {
            if (v.level == level) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知在店舞伴档位: " + level);
    }
}
