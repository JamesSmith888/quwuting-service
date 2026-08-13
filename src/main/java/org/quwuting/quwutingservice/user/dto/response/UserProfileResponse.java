package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户公开主页（GET /users/{id}，2026-08-12 礼物赠送者 → 用户详情页数据源）。
 * <p>
 * 隐私边界：只下发公开可展示字段（昵称/头像/角色/加入时间）与公开派生信息
 * （TA 创建的 NORMAL 舞伴）；不下发 openId、积分余额、流水等私人数据。
 * role 供前端渲染管理员徽标；joinedDays = 加入天数（前端展示"已加入 N 天"）。
 */
public record UserProfileResponse(
        Long id,
        String nickname,
        String avatarUrl,
        /** 角色（"USER"/"ADMIN"，前端据此渲染管理员徽标） */
        String role,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDateTime createdAt,
        /** 加入天数（createdAt → 今天，最小 0） */
        long joinedDays,
        /** TA 创建的公开舞伴（NORMAL，最近创建在前） */
        List<UserDancerResponse> dancers
) {}
