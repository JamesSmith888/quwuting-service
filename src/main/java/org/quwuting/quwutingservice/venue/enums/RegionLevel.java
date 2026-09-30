package org.quwuting.quwutingservice.venue.enums;

/**
 * 行政区划层级（qwt_regions.level，2026-09-30，MySQL V35）。
 * <p>
 * 只到「县级市」一层：平台 {@code qwt_venues.city} 来自地图逆地理，实测含县级市
 * （仙桃 / 天门 / 潜江 / 兴义 / 盘州 / 喀什 / 阿克苏），补上这一层后已收录的 108 个
 * 城市 <b>100% 命中</b>本名录（自洽性校验）。<b>有意不收「县 / 市辖区 / 街道」</b>：
 * 名字与门店名碰撞概率高，且用户以城市粒度找店。
 */
public enum RegionLevel {

    /** 省级（省 / 自治区 / 直辖市 / 特别行政区） */
    PROVINCE("省级"),

    /** 地级（地级市 / 自治州 / 地区 / 盟） */
    PREFECTURE("地级"),

    /** 县级市（省直辖县级市 / 地区辖县级市） */
    COUNTY_CITY("县级市");

    private final String label;

    RegionLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
