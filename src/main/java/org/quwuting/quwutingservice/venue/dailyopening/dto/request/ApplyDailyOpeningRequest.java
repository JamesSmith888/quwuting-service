package org.quwuting.quwutingservice.venue.dailyopening.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;

import java.time.LocalDate;

/**
 * 单条「门店每日营业快照」应用项（管线侧 MatchResult 镜像）。
 *
 * @param venueId    平台门店 ID（管线已匹配）
 * @param reportDate 信息源声明的营业日期
 * @param sourceId   渠道标识（xianbao360 / telegram / …）
 * @param status     信息源声称的当日状态（OPEN / CLOSED）
 * @param confidence 匹配置信度（EXACT/ALIAS 可自动反转，CONTAINED/FUZZY 仅落快照）
 */
public record ApplyDailyOpeningRequest(
        @NotNull(message = "venueId 不能为空")
        Long venueId,

        @NotNull(message = "reportDate 不能为空")
        LocalDate reportDate,

        @NotBlank(message = "sourceId 不能为空")
        @Size(max = 50, message = "sourceId 最长 50 字符")
        String sourceId,

        @NotNull(message = "status 不能为空")
        DailyOpeningStatus status,

        @NotNull(message = "confidence 不能为空")
        DailyOpeningConfidence confidence
) {}
