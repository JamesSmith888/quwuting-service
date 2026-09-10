package org.quwuting.quwutingservice.bulletin.dto.response;

/**
 * 快讯表态徽标（列表/详情下发；前端 chips 行即按本 DTO 渲染）。
 * <p>
 * <b>只下发 count &gt; 0 的 code</b>（展示 = 真实用户行为，创建入口在前端 Picker）——
 * 未有人表态的内容不渲染表态行，与门店 Reaction "至少一人参与才显示"同一不变量。
 * <p>
 * 无时间窗口字段（对比门店 {@code ReactionBadge} 的 countAll/count7d/count30d）：
 * 快讯不做窗口切片，计数即全部历史，见 47 号文档「快讯表态」。
 *
 * @param code      表态 code（{@link org.quwuting.quwutingservice.bulletin.BulletinReactionCode}）
 * @param emoji     表情字符（emoji 文本渲染，无图片资源）
 * @param label     中文短名（长按说明卡展示）
 * @param count     表态人数（一人一票 ⇒ 计数即人数）
 * @param reactedByMe 当前用户是否已投该表情（一人一票 ⇒ 整页至多一条为 true；未登录恒 false）
 */
public record BulletinReactionBadge(
        String code,
        String emoji,
        String label,
        long count,
        boolean reactedByMe
) {}
