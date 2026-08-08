package org.quwuting.quwutingservice.venue.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VenueHotProperties 配置缺省语义测试。
 * <p>
 * 覆盖 2026-08-08「热门场所标记」绝对门槛的容错契约：配置缺失/非法（≤0）时
 * 回退默认值 70（≈ 近30天 7 次收藏或 70 次浏览的最低活跃门槛）——防止配置笔误
 * 导致热门门槛失效（门槛退化为 0 = 无绝对下限，伪热门缺陷复现）。
 */
class VenueHotPropertiesTest {

    @Test
    void missingOrInvalidConfigFallsBackToDefault() {
        assertEquals(70, new VenueHotProperties(0).minHeatScore(), "配置 0 应回退默认门槛");
        assertEquals(70, new VenueHotProperties(-5).minHeatScore(), "配置负数应回退默认门槛");
    }

    @Test
    void validConfigIsPreserved() {
        assertEquals(15, new VenueHotProperties(15).minHeatScore());
    }
}
