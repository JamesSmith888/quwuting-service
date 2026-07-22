package org.quwuting.quwutingservice.taginteraction.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 维度评分请求（upsert 语义：首次=打分，再次=修改覆盖）。
 * tag 为评分维度名称，必须在系统定义的 RatingDimensions 列表中。
 */
public record ScoreTagRequest(
        @NotBlank(message = "评分维度不能为空")
        @Size(max = 50, message = "维度名称最长50字符")
        String tag,

        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最低1分")
        @Max(value = 10, message = "评分最高10分")
        Integer score
) {}
