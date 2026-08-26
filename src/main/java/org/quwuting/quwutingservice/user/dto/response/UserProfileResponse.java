package org.quwuting.quwutingservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户公开主页（GET /users/{id}，2026-08-12 礼物赠送者 → 用户详情页数据源）。
 * <p>
 * 隐私边界：只下发公开可展示字段（昵称/头像/角色/加入时间/积分余额）与公开派生
 * 信息（TA 创建的 NORMAL 舞伴）；不下发 openId、流水明细等私人数据。
 * role 供前端渲染管理员徽标；joinedDays = 加入天数（前端展示"已加入 N 天"）。
 * <p>
 * 2026-08-26 新增 pointsBalance（积分余额）：积分 = 平台内虚拟社区贡献值（非实名
 * 身份信息），且仅经「用户主动分享邀约 → 接收方（舞伴）查看」这一自愿展示场景下发
 * （邀约落地页访客信息卡数据源，见 AGENTS.md「小程序类目合规 UGC 红线」——与
 * 昵称/头像同属"用户自愿向具体接收方展示"的共享边界；若提审再次驳回 → 移除
 * 本字段与前端展示即可，邀约主卡不受影响）。
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
        /** 积分余额（qwt_points_accounts.balance 读写快照；无账户恒 0，邀请方粗判社区贡献） */
        long pointsBalance,
        /** TA 创建的公开舞伴（NORMAL，最近创建在前） */
        List<UserDancerResponse> dancers,
        /** 年龄（用户自主录入，null = 未填写；自愿分享通道下发，便于舞伴判断包时对象） */
        Integer age,
        /** 性别（MALE / FEMALE，null = 未声明；自愿分享通道下发） */
        String gender,
        /** 常驻城市（行政区划名，null = 未填写；自愿分享通道下发，便于同城包时匹配） */
        String city
) {}
