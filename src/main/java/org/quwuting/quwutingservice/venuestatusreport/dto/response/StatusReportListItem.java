package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;

import java.time.LocalDateTime;

/**
 * 门店暂停报列表项（GET /venues/{venueId}/status-reports，公开读）。
 * <p>
 * 详情页「报告暂停营业」弹层的默认内容：展示最近（TTL 窗口内）所有用户对该门店的
 * 暂停报告，供用户报告前了解社区信号（"已经有多人报告了"）——社区信号可溯源，
 * 避免"只有聚合数没有明细"的信任缺失（根因见 AGENTS.md「报告暂停营业弹层」）。
 * <p>
 * 隐私：{@code reporterName} 为脱敏昵称（首字 + "**"，无昵称回退「舞友」），
 * 不暴露完整用户身份；{@code mine} 供前端高亮"我"的报告。
 */
public record StatusReportListItem(
        /** 报告记录 ID */
        Long id,

        /** 报告者脱敏昵称（首字 + "**"，无昵称时"舞友"） */
        String reporterName,

        /** 暂停原因 */
        ReportReason reason,

        /** 暂停原因展示文案（"门店检查"/"情况不明"/"清场"） */
        String reasonDisplay,

        /** 报告时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        /** 是否当前登录用户的上报（未登录恒为 false） */
        boolean mine
) {}
