package org.quwuting.quwutingservice.taginteraction.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 标签点赞请求（toggle 语义：首次=点赞，再次=取消）。
 * tag 为标签文本，必须是该场所当前 tags 列表中存在的标签。
 */
public record LikeTagRequest(
        @NotBlank(message = "标签不能为空")
        @Size(max = 50, message = "标签最长50字符")
        String tag
) {}
