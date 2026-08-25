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
        DemandDetail demandDetail
) {

    /**
     * 需求说明详情（2026-08-26，解锁结果卡「需求说明」表格 + 三出口数据源）。
     * 全部来自 {@code recordDemand} 上下文（dancer/service/time/duration/location），
     * 零额外查询；空值行由前端渲染时省略（WXML 零三元，TS 派生 rows）。
     * <ul>
     *   <li>{@code dancerName}/{@code city}：舞伴昵称/城市（表格首行「舞伴 琳琳 · 上海」）；</li>
     *   <li>{@code serviceLabel}：本次服务权威 label（PACKAGE = 类别名 · 具体场景名，
     *       如「按时段 · KTV」；DANCE/ONLINE_CHAT = 类别名；OTHER = admin 录入服务内容）
     *       ——与需求描述服务部分同源（buildDemandServicePart）；</li>
     *   <li>{@code priceText}/{@code negotiable}：计费方式 + 朋友可议（表格计费行
     *       「300元/小时起 · 朋友可议」合并展示，negotiable=false 省略后半）；</li>
     *   <li>{@code locationScope}/{@code advanceNotice}/{@code rules}：服务地点范围/
     *       预约要求/规则说明（rules 空 → 前端兜底「未注明特别规则，可联系 TA 确认」）；</li>
     *   <li>{@code timeLabel}：本次时间（「M月D日」或「近3天内」）；</li>
     *   <li>{@code durationLabel}/{@code locationLabel}：时长/位置表态（未选/未开启
     *       恒 null，前端省略行）；</li>
     *   <li>{@code demandMessage}：单行验证消息原文（表格底部灰底块展示）；</li>
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
