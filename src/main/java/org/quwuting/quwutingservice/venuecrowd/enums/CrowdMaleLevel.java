package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 男客密度档位（门店热度次信号，2026-08-29）。
 * <p>
 * 业务语境：女舞伴选场看「女少男多」= 好赚钱、没竞争（docs/agents/27-venue-crowd-report.md）；
 * 男客可视作「能放开跳」的补充氛围。可空（= 跳过未观察，聚合不计票）。
 */
public enum CrowdMaleLevel {
    FEW(1, "少"),
    NORMAL(2, "正常"),
    MANY(3, "多");

    private final int level;
    private final String displayName;

    CrowdMaleLevel(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int getLevel() {
        return level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CrowdMaleLevel of(int level) {
        for (CrowdMaleLevel v : values()) {
            if (v.level == level) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知男客密度档位: " + level);
    }
}
