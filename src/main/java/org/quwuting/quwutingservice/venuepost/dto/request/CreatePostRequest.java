package org.quwuting.quwutingservice.venuepost.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布动态请求体。
 * publisherType 与 publisherName 由后端根据角色自动判定，不由客户端指定。
 */
public record CreatePostRequest(
        @NotBlank(message = "动态标题不能为空")
        @Size(max = 100, message = "动态标题最长100个字符")
        String title,

        @NotBlank(message = "动态内容不能为空")
        @Size(max = 2000, message = "动态内容最长2000个字符")
        String content
) {}
