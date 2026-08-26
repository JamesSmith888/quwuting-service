package org.quwuting.quwutingservice.points.dto;

import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

/**
 * 积分解锁响应（POST /points/unlock，2026-08-14）。
 * <ul>
 *   <li>{@code unlocked}：true = 本次成功解锁（或此前已解锁——幂等，不重复扣费）；
 *       false = 无门槛/目标不可见等（正常流程不会返回 false，防御性保留）；</li>
 *   <li>{@code balance}：解锁后的余额快照（前端据此本地更新余额显示，无需重拉概览）；</li>
 *   <li>{@code content}：解锁后可见的内容——照片 = 原图 URL；联系方式 = 联系方式文本。
 *       <b>仅解锁成功时返回</b>（未解锁不下发真实内容，防绕过）。</li>
 *   <li>{@code contactImageUrl}：联系方式图片 URL（2026-08-14 新增，二维码等）——
 *       仅 targetType=DANCER_CONTACT 且该舞伴填了联系方式图片时返回；
 *       照片解锁恒为 null。与 contact 同一门槛语义（解锁后一并下发）。</li>
 *   <li>{@code demandMessage}：添加好友需求描述（2026-08-24 新增，方案B 结构化格式
 *       去舞厅【服务 · 时间 · 时长】）——仅 targetType=DANCER_CONTACT
 *       且请求携带需求时返回（服务端拼接权威文案，前端零拼接）；照片/视频恒 null。</li>
 *   <li>{@code freeToday}：本次解锁是否命中「每日首次免费」（2026-08-24 新增；
 *       仅 DANCER_CONTACT 有意义，其余恒 false）——前端结果卡据此展示"今日首次 · 免费"。</li>
 *   <li>{@code demandDetail}：需求说明详情（2026-08-26 新增，21-demand-detail-card）
 *       ——仅 targetType=DANCER_CONTACT 且携带需求时非 null（照片/视频恒 null；
 *       旧客户端忽略新字段向后兼容）。含结构化字段（供结果卡「需求说明」表格渲染与
 *       离屏 canvas 图片绘制）与 {@code demandDetailText}（服务端权威拼接的多行
 *       详细文本，复制即用，前端零拼接）；{@code demandMessage} 为兼容保留
 *       （= demandDetail.demandMessage() 冗余）。</li>
 *   <li>{@code demandId} / {@code demandStatus} / {@code expireAt}（2026-08-26 新增，
 *       邀约中转，22-invite-relay-and-auto-release）——开启中转开关
 *       （contact_relay）的舞伴：{@code demandStatus}=PENDING 表示邀约已提交、
 *       等待舞伴批准（{@code content}/{@code demandMessage} 恒 null，前端渲染
 *       「等待回复」态）；=APPROVED/AUTO_RELEASED 表示已获批、联系方式照常下发
 *       （幂等直返）；{@code expireAt} = 24h 降级截止时间（PENDING 时下发，
 *       前端倒计时）。未开启中转舞伴恒 null，行为与旧版完全一致。</li>
 * </ul>
 */
public record UnlockResponse(
        boolean unlocked,
        long balance,
        PointsGateTargetType targetType,
        Long targetId,
        String content,
        String contactImageUrl,
        String demandMessage,
        boolean freeToday,
        DemandDetail demandDetail,
        Long demandId,
        String demandStatus,
        java.time.LocalDateTime expireAt
) {

    /**
     * 需求说明详情（2026-08-26，解锁结果卡「需求说明」表格 + 三出口数据源）。
     * 全部来自 {@code recordDemand} 上下文（dancer/service/time/duration/location），
     * 零额外查询；空值行由前端渲染时省略（WXML 零三元，TS 派生 rows）。
     * 2026-08-26 邀约瘦身：表格只渲染用户本次需求四要素行——服务/时间/时长/位置，
     * 值 = 详情表述（时间补「可协商」、位置 detailText 完整句，无字数限制语义说透）；
     * 舞伴静态信息字段（dancerName/city/priceText/locationScope/advanceNotice/rules）
     * 保留结构向后兼容，前端不再消费（TA 自己的信息无需在邀约重复）。
     * <ul>
     *   <li>{@code serviceLabel}：本次服务权威 label（PACKAGE = 类别名 · 具体场景名，
     *       如「按时段 · KTV」；DANCE/ONLINE_CHAT = 类别名；OTHER = admin 录入服务内容）
     *       ——与需求描述服务部分同源（buildDemandServicePart）；</li>
     *   <li>{@code timeLabel}：本次时间详情表述（「近3天内，具体哪天可与您协商」或
     *       「8月27日，具体时段可与您协商」）；</li>
     *   <li>{@code durationLabel}/{@code locationLabel}：时长/位置详情表述（未选/未开启
     *       恒 null，前端省略行；位置 = UserLocationOption.detailText 完整句）；</li>
     *   <li>{@code demandMessage}：单行验证消息原文（加好友用，保持精简文案）；</li>
     *   <li>{@code demandDetailText}：多行详细文本（出口 C 复制即用，服务端权威拼接）。</li>
     * </ul>
     */
    public record DemandDetail(
            String dancerName,
            String city,
            String serviceLabel,
            String priceText,
            boolean negotiable,
            String locationScope,
            String advanceNotice,
            String rules,
            String timeLabel,
            String durationLabel,
            String locationLabel,
            String demandMessage,
            String demandDetailText
    ) {}
}
