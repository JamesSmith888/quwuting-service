package org.quwuting.quwutingservice.recruitment.dto.response;

/**
 * 联系方式获取响应（POST /recruitments/{id}/contact，登录后实时返回，幂等不重复计数）。
 */
public record RecruitmentContactResponse(
        String contactName,
        String contactPhone,
        String contactWechat
) {
}
