package org.quwuting.quwutingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户上报相关配置。
 * <p>
 * 配置键：{@code app.reports.maintenance-days}（YAML）。
 * <p>
 * maintenanceDays 是平台对用户上报的处理承诺天数——用户提交"信息缺失/有误"
 * 上报后，前端提示"我们会在 X 日内维护好"中的 X 即来自此配置（后端组装
 * maintenanceHint 文案下发，前端禁止硬编码承诺天数）。
 */
@ConfigurationProperties(prefix = "app.reports")
public record ReportsProperties(int maintenanceDays) {

    /** 配置缺失时的安全回退（默认承诺 3 日内处理） */
    private static final ReportsProperties DEFAULT = new ReportsProperties(3);

    public ReportsProperties {
        if (maintenanceDays <= 0) {
            maintenanceDays = DEFAULT.maintenanceDays();
        }
    }

    public int maintenanceDays() {
        return maintenanceDays;
    }
}
