package org.quwuting.quwutingservice.venuefeedback.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;

/**
 * 场所信息纠错反馈请求体。
 * <p>
 * type 为必填（用户选择具体问题类型）；note 为可选补充说明。
 * <p>
 * 结构化纠错载荷（2026-08-10 新增）：type=INACCURATE（信息有误）时可携带
 * field（哪个字段有误，受控词汇表 {@link FeedbackField}）+ correctedValue
 * （用户认为正确的数据）——解决旧载荷只有自由文本 note、管理端无法机器可读
 * 核对纠错建议的问题（根因见后端 AGENTS.md「统一用户上报 → 结构化纠错载荷」）。
 * 两者均可选：只指出字段（不提供纠正值）或只提供纠正值（不指定字段）都是
 * 有效上报；字段词汇表非法值由 Jackson 枚举反序列化 400 拦截。
 */
public record CreateFeedbackRequest(
        @NotNull(message = "反馈类型不能为空")
        FeedbackType type,

        @Size(max = 500, message = "补充说明最多 500 字")
        String note,

        /** 纠错目标字段（可选，type=INACCURATE 时使用；其余类型忽略） */
        FeedbackField field,

        /** 用户认为正确的数据（可选，最多 500 字；与 field 配套） */
        @Size(max = 500, message = "正确信息最多 500 字")
        String correctedValue
) {}
