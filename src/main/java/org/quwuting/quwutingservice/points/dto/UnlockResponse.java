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
        boolean freeToday
) {}
