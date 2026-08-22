package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德地图 Web 服务配置（2026-08-21 新增，管理端「一键同步门店图片」）。
 * <p>
 * 配置键：{@code app.amap.key}（YAML，生产经环境变量 {@code AMAP_KEY} 注入）。
 * <p>
 * 背景：存量门店缺主图（image_url 为空），管理端「同步门店图片」调用高德
 * Web 服务 place/text 关键词搜索（extensions=all 返回 photos[]），取官方图床
 * URL 直接写入 image_url——不下载图片到 Supabase Storage（省存储 + 高德 CDN
 * 直出）。key 只放后端，不落前端（与 app.geocode.key 同合规策略）。
 */
@ConfigurationProperties(prefix = "app.amap")
public record AmapProperties(String key) {

    /** 配置缺失时的安全回退：空 key（调用时给出明确错误） */
    private static final AmapProperties DEFAULT = new AmapProperties("");

    public AmapProperties {
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
