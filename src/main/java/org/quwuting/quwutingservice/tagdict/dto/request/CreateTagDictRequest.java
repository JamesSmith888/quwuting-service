package org.quwuting.quwutingservice.tagdict.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.tagdict.enums.TagScope;

/**
 * 管理员新增标签请求体（POST /admin/tag-dict，仅 ADMIN）。
 * scope 缺省 = DANCER（前端恒传 DANCER；VENUE 为门店迁移预留）。
 */
public record CreateTagDictRequest(
        @NotBlank(message = "标签名不能为空")
        @Size(max = 20, message = "标签名最长20个字符")
        String text,

        @Size(max = 300, message = "说明最长300个字符")
        String description,

        /** 适用领域（缺省 = DANCER，见 {@link TagScope}） */
        TagScope scope
) {}
