package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯位置服务地理编码配置。
 * <p>
 * 配置键：{@code app.geocode.key}（YAML，生产经环境变量 {@code QQMAP_KEY} 注入）。
 * <p>
 * 背景（2026-08-11）：存量门店缺经纬度（导航依赖 wx.openLocation），管理端
 * 「批量补齐坐标」调用腾讯位置服务 WebService 地理编码 API（输出 gcj02，与前端
 * 全链路坐标约定一致，见 quwuting/miniprogram/utils/geo.ts）。key 只放后端，
 * 不落前端（合规要求）。
 */
@ConfigurationProperties(prefix = "app.geocode")
public record GeocodeProperties(String key) {

    /** 配置缺失时的安全回退：空 key（调用地理编码时给出明确错误） */
    private static final GeocodeProperties DEFAULT = new GeocodeProperties("");

    public GeocodeProperties {
        if (key == null) {
            key = "";
        }
    }

    public String key() {
        return key;
    }

    /** key 是否已配置 */
    public boolean isConfigured() {
        return !key.isBlank();
    }
}
