package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;

import java.time.LocalDateTime;

/**
 * 管理端舞伴列表项（GET /admin/dancers，仅 ADMIN）。
 * <p>
 * 审核场景管理员需要"谁注册的"信息（createdBy → qwt_users 昵称/头像，
 * 用户已删除时回退占位文案）；列表按提交时间倒序（新注册优先审核），
 * status 驱动前端筛选与 PENDING 卡片的内联「通过/驳回」操作。
 */
public record AdminDancerResponse(
        Long id,
        String nickname,
        String avatarUrl,
        String bio,
        String gender,
        String city,
        DancerStatus status,
        /**
         * 信息核验状态（2026-08-14 官方认证）。管理端待办提示：PENDING_REVIEW =
         * 舞伴本人修改资料后待复核（卡片内联「确认核验 / 撤销」操作）；
         * VERIFIED = 已认证（可撤销）；UNVERIFIED = 未认证（可授予）。
         */
        DancerVerificationStatus verificationStatus,
        /** 最近一次授予认证的时间（仅 VERIFIED 时有值；审计历史在 qwt_dancer_verification_logs） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime verifiedAt,
        /** 注册人昵称（createdBy → qwt_users；用户已删时回退"未知用户"） */
        String creatorNickname,
        /** 注册人头像（qwt_users.avatar_url，2026-08-08 起为用户主动设置的真实头像；未设置时为 null） */
        String creatorAvatarUrl,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt
) {}
