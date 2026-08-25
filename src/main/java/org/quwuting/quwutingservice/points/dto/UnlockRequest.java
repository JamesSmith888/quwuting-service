package org.quwuting.quwutingservice.points.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

import java.util.List;

/**
 * 积分解锁请求（POST /points/unlock，2026-08-14）。
 * <p>
 * 幂等语义：已解锁（存在解锁记录）直接返回 unlocked=true + 内容，<b>不重复扣费</b>
 * （一人一目标只扣一次费，UNIQUE 兜底并发，见 PointsUnlock）。
 * 校验链（服务层）：门槛存在（cost>0）→ 目标对当前用户可见 → 余额足够 →
 * 原子扣减 → 写解锁流水（source_type=UNLOCK，单向燃烧）→ 写解锁记录。
 * <p>
 * 2026-08-24 联系方式需求（需求 4/5）：targetType=DANCER_CONTACT 时可携带
 * {@code demand}（本次需求：服务/时间<b>各选 1 项</b> + 时长可选）——服务端据此
 * 生成添加好友需求描述（方案B 结构化格式，前缀小程序名「去舞厅」）并随需求记录
 * 落库（风控留痕）。照片/视频解锁不消费 demand（忽略）。
 */
public record UnlockRequest(
        @NotNull(message = "解锁目标类型不能为空")
        PointsGateTargetType targetType,

        @NotNull(message = "解锁目标不能为空")
        Long targetId,

        /** 联系方式需求（2026-08-24，仅 DANCER_CONTACT 消费；可为 null） */
        @Valid
        DemandSelection demand
) {

    /**
     * 联系方式需求选择（2026-08-24；2026-08-24 晚改版：服务/时间各选 1 项——
     * 逼迫用户精准需求，消息拼接无「或」；2026-08-25 改版：时间 = 具体日期；
     * 2026-08-26 优化：时间新增「近3天内」相对槽 + 按时段服务可携带子选项 +
     * 位置表态（location））。
     * <ul>
     *   <li>{@code serviceIds}：选中的服务 id（qwt_dancer_services.id，恰好 1 个，
     *       须属于目标舞伴且在用）；</li>
     *   <li>{@code timeSlots}：选中的时间（恰好 1 个：相对槽「近3天内」code
     *       {@code WITHIN_3_DAYS}，或具体日期 YYYY-MM-DD 且须落在 [今天, 今天+6]
     *       窗口——今天起 7 天快捷选项）；</li>
     *   <li>{@code duration}：时长（DemandDuration 枚举 code，可空 = 未选）；</li>
     *   <li>{@code subCategory}：按时段子类别 code（DancerServiceSubCategory，仅服务
     *       category=PACKAGE 时必填——需求弹层「具体场景」单选，默认 KTV；其余类别忽略）；</li>
     *   <li>{@code location}：位置表态（2026-08-26，UserLocationOption 枚举 code：
     *       SAME_CITY 同城 / WILL_TRAVEL 非同城·自行前往——相对关系而非真实地址）。
     *       仅目标舞伴开启「加好友需告知位置」（require_user_location）时<b>必填</b>
     *       （缺失/非法 → 1001）；未开启时忽略本字段。</li>
     * </ul>
     */
    public record DemandSelection(
            @Size(min = 1, max = 1, message = "服务请选择 1 项")
            List<Long> serviceIds,

            @Size(min = 1, max = 1, message = "时间请选择 1 个")
            List<String> timeSlots,

            String duration,

            /** 按时段子类别 code（仅 PACKAGE 服务必填；其余类别忽略） */
            String subCategory,

            /** 位置表态 code（仅舞伴开启「加好友需告知位置」时必填；其余忽略） */
            String location
    ) {}
}
