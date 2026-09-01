package org.quwuting.quwutingservice.venuecrowd.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理端门店热度单条上报明细（2026-09-01，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 管理端「热度管理」按店下钻数据源：运营定位「哪条上报不合理/错误」后删除
 * （DELETE /admin/crowd-reports/{id}）。字段服务端权威——badgeText 三档标识
 * （资深/常客/普通）、档位名/锚点、reportDate（每日一记归属日）、
 * modifyCount（同日修改次数，≥3 刷量/反复横跳嫌疑）、绝对时间。
 */
public record AdminCrowdReportDetail(
        Long id,
        Long userId,
        String nickname,
        /** 用户标识分档（资深/常客/普通，与公共面 badgeFor 同语义） */
        String badgeText,
        int femaleLevel,
        String femaleName,
        String femaleHint,
        Integer maleLevel,
        String maleName,
        LocalDate reportDate,
        /** 同日修改次数（modify_count；0 = 当日首报） */
        int modifyCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {
}
