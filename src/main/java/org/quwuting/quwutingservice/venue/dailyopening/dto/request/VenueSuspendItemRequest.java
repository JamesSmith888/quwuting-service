package org.quwuting.quwutingservice.venue.dailyopening.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 单条「当日舞讯白名单外 → 置暂停营业」应用项（2026-09-10，白名单口径反向写库）。
 * <p>
 * 与 {@link ApplyDailyOpeningRequest} 的区别：本项表达的是<b>未上榜即暂停</b>的推断，
 * 不携带 status/confidence——舞讯是「当日营业白名单」，某城被点名覆盖、而本店不在名单内
 * = 今日未营业；城市级覆盖是精确事实，无需匹配置信度。
 *
 * @param venueId    平台门店 ID（调用方已按「同城 + 未上榜」筛出）
 * @param reportDate 信息源声明的覆盖日期（审计留痕；写库不落该字段）
 * @param sourceId   渠道标识（xianbao360 / telegram / …），多源合并时填合并标识
 * @param source     变更来源标识（AGENT_BATCH=Agent+Skill 批量落库 / ADMIN=管理端人工写库）
 */
public record VenueSuspendItemRequest(
        @NotNull(message = "venueId 不能为空")
        Long venueId,

        @NotNull(message = "reportDate 不能为空")
        LocalDate reportDate,

        @NotBlank(message = "sourceId 不能为空")
        @Size(max = 50, message = "sourceId 最长 50 字符")
        String sourceId,

        @Size(max = 20, message = "source 最长 20 字符")
        String source
) {}
