package org.quwuting.quwutingservice.points.dto;

import java.time.LocalDateTime;

/**
 * 我的邀约详情（2026-08-26，GET /points/demands/{id}）。
 * <p>
 * 语义：邀约 = 用户自己的行为记录，点击列表行进入<b>邀约详情</b>（而非舞伴主页）——
 * 展示这条邀约的完整内容：验证消息 message + 需求四要素表格（服务/时间/时长/位置，
 * 服务端权威详情表述）+ 舞伴摘要 + 创建时间。
 * <p>
 * 服务/时间/时长/位置从 qwt_demand_records 反推（recordDemand 落库的枚举/id 串）：
 * 服务 = 当前权威 label（<b>历史记录未存 subCategory，无法还原「按时段 · KTV」子选项，
 * 用服务当前 label 兜底</b>，与详情页服务卡同源；服务已软删/下架 → null，前端省略该行）；
 * 时间/时长/位置 = DemandDuration / UserLocationOption 枚举 display 详情表述（parse 失败 =
 * 历史数据异常 → 防御性 null）。demandDetailText = 服务端权威多行文本（复制即用，前端零拼接）。
 * <p>
 * 2026-08-26 邀约中转（22 号文档）：加 {@code status}/{@code statusText}/{@code expireAt}/
 * {@code contactText}/{@code contactImageUrl}/{@code demandMessage}——
 * {@code statusText} = 客人侧状态文案（服务端权威，尊重友好原则，前端零拼接）；
 * 联系方式字段<b>仅本人 + APPROVED/AUTO_RELEASED 时下发</b>（PENDING/REJECTED/EXPIRED
 * 恒 null，防联系方式随未获批状态泄漏；null = 存量记录，前端按现状渲染）。
 * <p>
 * 2026-08-27 履约闭环（docs/agents/23）：加 {@code fulfilledAt}/{@code cooperationCount}——
 * fulfilledAt = 本次邀约履约确认时间（null = 未确认，前端渲染「确认完成邀约」按钮）；
 * cooperationCount = 该客人与该舞伴的履约确认数（含本次，「与 TA 已合作 N 次」）。
 * <p>
 * 2026-08-27 拒绝原因 + 替代邀约（docs/agents/24）：加 {@code rejectReason}/
 * {@code rejectReasonText}（REJECTED 且管理员已填原因时下发——客人侧知因文案
 * 「TA 暂时不方便（档期冲突）」，前端 display = rejectReasonText || statusText，
 * 零拼接）；{@code rescueRequested}（非空 = 客人已请求平台代找替代，终态卡按钮
 * 变已请求态）；{@code originDemandId}（非空 = 本邀约是平台代找的替代邀约，
 * 前端展示「平台代找」标识）。
 */
public record DemandDetailResponse(
        Long id,
        Long dancerId,
        String dancerNickname,
        String dancerAvatarUrl,
        String dancerCity,
        boolean dancerVisible,
        String message,
        String serviceLabel,
        String timeLabel,
        String durationLabel,
        String locationLabel,
        String demandDetailText,
        String status,
        String statusText,
        java.time.LocalDateTime expireAt,
        String contactText,
        String contactImageUrl,
        LocalDateTime createdAt,
        LocalDateTime fulfilledAt,
        long cooperationCount,
        String rejectReason,
        String rejectReasonText,
        boolean rescueRequested,
        Long originDemandId) {
}
