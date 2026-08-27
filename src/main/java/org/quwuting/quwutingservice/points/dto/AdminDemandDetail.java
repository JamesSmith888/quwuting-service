package org.quwuting.quwutingservice.points.dto;

import java.time.LocalDateTime;

/**
 * 管理端邀约单详情（2026-08-26，GET /admin/demands/{id}；22 号文档）。
 * <p>
 * 语义：邀约工作台行点击 → 完整邀约单——客人公开资料（昵称/头像/加入天数，
 * 用户主动提交邀约 = 默示授权该舞伴/平台侧展示）+ 舞伴摘要 + 需求四要素结构化
 * 字段（服务/时间/时长/位置 = 服务端权威详情表述，与客人侧 getMyDemand 同源
 * 派生）+ demandDetailText（多行文本复制即用）+ message 原文（添加好友验证消息）
 * + over12h（等待超 12h = 建议管理员微信催办一次）+ status（邀约状态，非 PENDING
 * 时前端禁用发放/拒绝）。
 * <p>
 * 2026-08-27 履约闭环（docs/agents/23）：加 {@code cooperationCount}——该客人与
 * 该舞伴的履约确认数（「与 TA 已合作 N 次」），管理员转发邀约时可参考/告知舞伴
 * （私域信号，不公开广播）。
 * <p>
 * 隐私克制（同待办列表）：不下发客人真实联系方式（openId 等）；只有 message
 * 需求文本。
 */
public record AdminDemandDetail(
        Long id,
        LocalDateTime createdAt,
        Long dancerId,
        String dancerNickname,
        String dancerCity,
        String dancerAvatarUrl,
        Long userId,
        String userNickname,
        String userAvatarUrl,
        long userJoinedDays,
        boolean over12h,
        String message,
        String serviceLabel,
        String timeLabel,
        String durationLabel,
        String locationLabel,
        String demandDetailText,
        String status,
        /** 该客人与该舞伴的履约确认数（「与 TA 已合作 N 次」，私域信号） */
        long cooperationCount) {
}
