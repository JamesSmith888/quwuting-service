package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 男客数量档位（门店热度次信号，2026-08-29 初版为「少/正常/多」密度三档；
 * 2026-08-29 用户改判：男客与在店舞伴（女）同构——冷清/一般/不错/火爆四档，
 * 锚点同为人数评估口径 0-20 / 约50 / 约100 / 300+）。
 * <p>
 * 业务语境：女舞伴选场看「女少男多」= 好赚钱、没竞争（docs/agents/27-venue-crowd-report.md）；
 * 男客可视作「能放开跳」的补充氛围。可空（= 跳过未观察，聚合不计票）。
 * <p>
 * 旧值兼容：初版 1=少 / 2=正常 / 3=多 在新语义下单调映射为 1=冷清 / 2=一般 /
 * 3=不错（数据 6 小时窗口自动过期，未做数据迁移）。
 */
public enum CrowdMaleLevel {
    COLD(1, "冷清", "0-20"),
    NORMAL(2, "一般", "约50"),
    GOOD(3, "不错", "约100"),
    PACKED(4, "火爆", "300+");

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
