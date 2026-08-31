package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 在店舞伴（女）数量档位（门店热度主信号，2026-08-29 新增，2026-08-31 细粒度重构）。
 * <p>
 * 细粒度档位：0-20、约30、约50、约80、约100、约150、约200、约300+（共 8 档，去除冷清/一般/不错/火爆等中文定性词）。
 * <p>
 * 契约：{@code level} 为落库值（smallint，1-8）；{@code displayName} 与
 * {@code anchor} 为展示文案（前端零拼接，仅渲染后端权威值）。
 */
public enum CrowdFemaleLevel {
    RANGE_0_20(1, "0-20", "0-20"),
    RANGE_30(2, "约30", "约30"),
    RANGE_50(3, "约50", "约50"),
    RANGE_80(4, "约80", "约80"),
    RANGE_100(5, "约100", "约100"),
    RANGE_150(6, "约150", "约150"),
    RANGE_200(7, "约200", "约200"),
    RANGE_300_PLUS(8, "约300+", "约300+");

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
