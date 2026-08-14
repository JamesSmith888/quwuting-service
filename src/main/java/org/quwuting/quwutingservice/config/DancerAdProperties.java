package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 舞伴创作者收益计划配置（2026-08-14，配置键 {@code app.dancer-ad.*}）。
 * <p>
 * 广告位 ID 是运营可调参数（更换广告位/测试广告位无需发版）——由后端下发到
 * 舞伴详情响应（earningsAdUnitId），前端零硬编码（广告位 ID 非敏感密钥，
 * 但跟随项目"key 只放后端、前端经接口下发"惯例，且可运营热调）。
 */
@ConfigurationProperties(prefix = "app.dancer-ad")
public record DancerAdProperties(String adUnitId) {

    /** 配置缺失时的安全回退（空 = 未配置广告位，前端不渲染广告入口） */
    private static final DancerAdProperties DEFAULT = new DancerAdProperties("");

    public DancerAdProperties {
        if (adUnitId == null) {
            adUnitId = "";
        }
    }

    public static DancerAdProperties getDefault() {
        return DEFAULT;
    }
}
