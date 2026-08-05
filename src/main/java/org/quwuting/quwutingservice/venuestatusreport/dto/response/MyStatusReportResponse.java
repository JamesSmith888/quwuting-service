package org.quwuting.quwutingservice.venuestatusreport.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 当前用户的状态上报记录（GET /status-reports/mine）。
 * <p>
 * 仅返回未撤销（deleted=false）的记录，含已过期（TTL 外）——「已过期」记录提醒用户可重新上报。
 * <p>
 * {@code active} / {@code expiresAt} 由后端按 {@code ACTIVE_REPORT_TTL_HOURS} 常量计算
 * （TTL 唯一事实源在后端），前端展示剩余时间只做 expiresAt - now 的纯计算，不持有 TTL 常量。
 */
public record MyStatusReportResponse(
        /** 报告记录 ID */
        Long id,

        /** 场所 ID（前端据此跳转场所详情页） */
        Long venueId,

        /** 场所名称（场所逻辑删除后仍返回原名，保留记录真实性） */
        String venueName,

        /** 场所城市 */
        String venueCity,

        /** 场所区县 */
        String venueDistrict,

        /** 场所详细地址（可能为空串） */
        String venueAddress,

        /** 报告时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        /** 是否仍处活跃 TTL 窗口内（后端判定，驱动「生效中/已过期」展示） */
        boolean active,

        /** 过期时刻（createdAt + TTL，active=true 时有意义，前端据此展示剩余时间） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expiresAt
) {}
