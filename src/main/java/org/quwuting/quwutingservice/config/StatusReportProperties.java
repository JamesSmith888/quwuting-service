package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 场所突发事件（实时众包信号）模块配置。
 * <p>
 * 配置键：{@code app.status-report.*}（YAML）。展示窗口是运营可调参数——
 * 改配置重启即生效，禁止业务硬编码（与 PointsProperties / ReportsProperties 同模式）。
 */
@ConfigurationProperties(prefix = "app.status-report")
public record StatusReportProperties(
        /**
         * 门店「最近突发事件」列表的展示窗口（小时，2026-08-12 新增）。
         * <p>
         * 列表展示 = 未撤销的报告事实（活跃 + 已过期），窗口按报告行为时间
         * {@code created_at >= now - window} 裁剪（防无限增长；cutoff 由 Service
         * 层传入，SQL 层禁止自行定义时间窗）。窗口必须 ≥ 最大类型 TTL（恢复营业
         * 24h）——否则最长 TTL 的信号一过期即脱离展示窗口，"过期仍可见"语义落空。
         * 过期标注（{@code expired}）由 Service 层按 {@code expires_at} 列判定，
         * 与全局活跃判定口径一致（TTL 唯一事实源 = 列）。
         */
        int recentHistoryHours
) {

    /** 配置缺失时的安全回退（默认 48h：过期信号仍保留 2 天展示期，足够用户回看上下文） */
    private static final StatusReportProperties DEFAULT = new StatusReportProperties(48);

    public StatusReportProperties {
        if (recentHistoryHours <= 0) {
            recentHistoryHours = DEFAULT.recentHistoryHours();
        }
    }
}
