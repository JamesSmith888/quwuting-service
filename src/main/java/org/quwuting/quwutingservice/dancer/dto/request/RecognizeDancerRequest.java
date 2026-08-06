package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 认可舞伴请求体（POST /dancers/{id}/recognitions，toggle 语义：今日已认可 → 取消）。
 * <p>
 * tags 为可选的字典标签列表（用户认可行为的标签来源），服务层校验：
 * 每个 tag 必须命中 {@code DancerTagCode} 字典、去重后最多
 * {@code DancerService.MAX_TAGS_PER_RECOGNITION} 个（空列表 = 纯认可不带标签）。
 * 取消认可（今日已有记录）时请求体可省略（tags 不参与取消语义，级联删除当日标签）。
 */
public record RecognizeDancerRequest(
        @Size(max = 10, message = "标签数量过多")
        List<String> tags
) {}
