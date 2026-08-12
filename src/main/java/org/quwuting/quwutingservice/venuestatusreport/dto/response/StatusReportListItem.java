package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;

import java.time.LocalDateTime;

/**
 * 门店突发事件报告列表项（GET /venues/{venueId}/status-reports，公开读）。
 * <p>
 * 详情页「报告突发事件」弹层的默认内容：展示最近（展示窗口 = 报告行为时间
 * {@code app.status-report.recent-history-hours} 内）所有用户对该门店的突发报告
 * （含已过期，{@code expired} 标注），供用户报告前了解社区信号（"已经有多人报告了"）
 * 与回看历史上下文——社区信号可溯源，避免"只有聚合数没有明细"的信任缺失
 * （根因见 AGENTS.md「报告暂停营业弹层」）。
 * <p>
 * 2026-08-11 泛化：reason → type（8 类突发事件），新增 severity（严重级，前端色阶
 * 直接消费，禁止前端自行映射）。
 * 2026-08-12 新增 {@code expired}：TTL 过期只代表信号失效，不代表报告事实消失——
 * 过期记录仍展示并标注（与「我的上报记录」active 标注同一语义，见 AGENTS.md）。
 * <p>
 * 隐私：{@code reporterName} 为脱敏昵称（首字 + "**"，无昵称回退「舞友」），
 * 不暴露完整用户身份；{@code mine} 供前端高亮"我"的报告。
 */
public record StatusReportListItem(
        /** 报告记录 ID */
        Long id,

        /** 报告者脱敏昵称（首字 + "**"，无昵称时"舞友"） */
        String reporterName,

        /** 突发事件类型 */
        ReportType type,

        /** 类型展示文案（"突然检查"/"暂停营业"/"恢复营业"等） */
        String typeDisplay,

        /** 严重级（high/medium/low/recovery，前端色阶直接消费） */
        String severity,

        /** 报告时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        /** 信号是否已过期（expires_at <= 当前时刻，TTL 唯一事实源 = expires_at 列；前端标注灰显） */
        boolean expired,

        /** 是否当前登录用户的上报（未登录恒为 false） */
        boolean mine
) {}
