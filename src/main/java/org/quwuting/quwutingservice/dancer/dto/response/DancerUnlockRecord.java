package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

import java.time.LocalDateTime;

/**
 * 舞伴解锁记录明细（2026-08-26 新增，「解锁信息」条形点击 → 详情页数据源）。
 * <p>
 * 语义 = "谁在什么时间解锁了舞伴的哪类内容、花了多少积分"——对舞伴本人/运营是
 * 内容价值的直接反馈（谁愿意为我的照片/短视频/联系方式付费），与礼物墙
 * {@code GifterResponse}（谁送了什么礼物）同源的社会证明先例。
 * <p>
 * 口径：
 * <ul>
 *   <li>{@code userId/nickname/avatarUrl} = 解锁用户公开资料（JOIN qwt_users，
 *       软删用户排除；昵称缺失回退前端占位文案）；</li>
 *   <li>{@code targetDesc} = 内容描述（照片 = "照片 N"（N = 相册展示序号）、
 *       短视频 = "短视频 · m:ss"（时长）、联系方式 = "联系方式"）——后端权威派生，
 *       前端零拼接；</li>
 *   <li>{@code createdAt} = 解锁时间（qwt_points_unlocks.created_at，倒序）；</li>
 *   <li>{@code cost} = 本次花费积分（关联扣费流水 -delta；免费解锁
 *       transaction_id 为 null → 0）。</li>
 * </ul>
 * 接口公开只读（对齐 stats/gifters 先例：响应为用户公开资料 + 行为时间，无身份
 * 敏感字段）；按解锁时间倒序（最新解锁在前），解锁低频无分页。
 */
public record DancerUnlockRecord(
        /** 解锁用户 ID */
        Long userId,
        /** 解锁用户昵称（可空——已注销/缺失回退前端占位文案） */
        String nickname,
        /** 解锁用户头像 URL（可空） */
        String avatarUrl,
        /** 内容类型（DANCER_PHOTO / DANCER_VIDEO / DANCER_CONTACT） */
        PointsGateTargetType targetType,
        /** 内容类型展示名（"照片" / "短视频" / "联系方式"，后端权威） */
        String targetLabel,
        /** 内容描述（照片序号 / 短视频时长 / 联系方式，后端权威派生） */
        String targetDesc,
        /** 解锁时间 */
        LocalDateTime createdAt,
        /** 本次花费积分（免费解锁 = 0） */
        int cost
) {
}
