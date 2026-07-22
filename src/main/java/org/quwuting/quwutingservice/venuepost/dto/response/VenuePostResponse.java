package org.quwuting.quwutingservice.venuepost.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venuepost.enums.PostPublisherType;

import java.time.LocalDateTime;

/**
 * 场所动态响应体。
 *
 * @param publisherTypeDisplay 发布方类型展示名（"商家" / "平台"），前端直接渲染
 */
public record VenuePostResponse(
        Long id,
        Long venueId,
        String title,
        String content,
        PostPublisherType publisherType,
        String publisherTypeDisplay,
        String publisherName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
