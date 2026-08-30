package org.quwuting.quwutingservice.recruitment.dto.request;

/**
 * 发布请求体（POST /admin/recruitments/{id}/publish）。
 * <p>
 * 描述/薪资命中风险词（押金/培训费/进群类诈骗与导流话术）时，首次发布被拒
 * （code 1010，message 携带命中词）——管理员人工确认无违规后 confirmed=true 强制放行。
 */
public record PublishRecruitmentRequest(
        Boolean confirmed
) {
}
