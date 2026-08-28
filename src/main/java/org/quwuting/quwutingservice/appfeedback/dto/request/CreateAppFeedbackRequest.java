package org.quwuting.quwutingservice.appfeedback.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;

/**
 * 平台级意见反馈提交请求体（POST /app-feedbacks，匿名可提交）。
 * <p>
 * 低门槛设计（2026-08-28，产品原则 = 用户没有任何动力为平台做事）：
 * category 必填（四类 chips 一键选择即完成结构化）、content 必填但允许极短
 * （1 字也算表达，防空提交保证管理端可读）、imageUrl 可选（最多 1 张截图，
 * Supabase 直传后回填 publicUrl；上传需登录，未登录用户仅文字提交）。
 */
public record CreateAppFeedbackRequest(
        @NotNull(message = "反馈分类不能为空")
        AppFeedbackCategory category,

        @NotBlank(message = "请填写反馈内容")
        @Size(max = 500, message = "反馈内容最多 500 字")
        String content,

        @Size(max = 512, message = "截图地址过长")
        String imageUrl
) {}
