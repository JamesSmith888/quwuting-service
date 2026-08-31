package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 男客数量档位（门店热度次信号，2026-08-29 新增，2026-08-31 细粒度重构）。
 * <p>
 * 细粒度档位与在店舞伴同构：0-20、约30、约50、约80、约100、约150、约200、约300+（共 8 档，去除中文定性词）。
 * <p>
 * 业务语境：女舞伴选场看「女少男多」= 好赚钱、没竞争（docs/agents/27-venue-crowd-report.md）；
 * 男客可视作「能放开跳」的补充氛围。可空（= 跳过未观察，聚合不计票）。
 */
public enum CrowdMaleLevel {
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

    CrowdMaleLevel(int level, String displayName, String anchor) {
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

    public static CrowdMaleLevel of(int level) {
        for (CrowdMaleLevel v : values()) {
            if (v.level == level) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知男客数量档位: " + level);
    }
}
