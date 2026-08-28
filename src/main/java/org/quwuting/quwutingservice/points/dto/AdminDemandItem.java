package org.quwuting.quwutingservice.points.dto;

import java.time.LocalDateTime;

/**
 * 管理端邀约工作台待办行（2026-08-26，GET /admin/demands/pending；22 号文档）。
 * <p>
 * 语义：开启邀约中转（contact_relay）舞伴的 PENDING 邀约——管理员微信人工
 * 转发给舞伴的素材。行 = 舞伴摘要 + 客人摘要（公开资料：昵称/头像/加入天数，
 * 用户主动提交邀约 = 默示授权该舞伴/平台侧展示）+ message 原文（服务端权威
 * 需求描述，已含四要素，管理员复制转发话术直接拼装）+ over12h（等待超 12h =
 * 建议管理员微信催办一次，降级前缓冲）。
 * <p>
 * 隐私克制：不下发客人真实联系方式（openId 等）；只有 message 需求文本。
 * <p>
 * 2026-08-26 工作台历史视图：新增 {@code status}（DemandStatus code，可空）——列表行
 * 自描述，使"已处理/全部"视图无需再查详情即可渲染状态徽标；Pending 列表同样下发
 * （值恒 PENDING），前端按 scope 决定是否展示，避免列表/详情双口径。
 * <p>
 * 2026-08-27 拒绝原因 + 信任信号（docs/agents/24）：{@code rejectReason}（拒绝原因
 * code，已处理视图展示标签）/ {@code rescueRequested}（客人已请求平台代找替代——
 * 高亮优先处理）/ {@code cooperationCount} + {@code contributionLevelName}（客人
 * 信任信号，转发话术拼装「已确认合作 N 次 · 等级称号」——舞伴一眼判断客人诚意）。
 */
public record AdminDemandItem(
        Long id,
        LocalDateTime createdAt,
        Long dancerId,
        String dancerNickname,
        String dancerCity,
        Long userId,
        String userNickname,
        String userAvatarUrl,
        long userJoinedDays,
        String message,
        boolean over12h,
        String status,
        String rejectReason,
        boolean rescueRequested,
        long cooperationCount,
        String contributionLevelName,
        /**
         * 客人反馈 code（2026-08-27，V56，docs/agents/25「反馈闭环」；
         * DemandGuestFeedback：非空 = 客人对该邀约提交了「没加上 TA？」反馈
         * （已自动返还扣费积分）——管理端识别需人工介入的邀约（微信侧核实/安抚）。
         */
        String guestFeedback,
        /**
         * 反馈是否已核实（2026-08-28，V58，docs/agents/25「反馈闭环 · 管理端
         * 可见性修复」）：管理端已微信侧核实并归档（guest_feedback_handled_at
         * 非空）。待处理视图 = false（反馈待办行，提供「标记已核实」操作）；
         * 已处理/全部视图 = true（「已核实」标记，与普通已处理行区分）。
         */
        boolean guestFeedbackHandled) {
}
