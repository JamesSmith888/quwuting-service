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
        LocalDateTime createdAt) {
}
