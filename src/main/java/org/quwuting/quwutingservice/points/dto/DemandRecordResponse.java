package org.quwuting.quwutingservice.points.dto;

import java.time.LocalDateTime;

/**
 * 我的邀约记录（2026-08-26，GET /points/demands/mine 行）。
 * <p>
 * 语义：用户每次获取舞伴联系方式时强制填写邀约（服务端生成添加好友验证消息并落库
 * qwt_demand_records），本响应 = 个人中心「我的邀约」列表数据源——只展示<b>自己的</b>
 * 记录（按 userId 过滤，天然隔离），行 = 舞伴摘要 + 需求描述原文 + 创建时间。
 * <p>
 * 舞伴摘要（dancerNickname/avatarUrl/city）来自 qwt_dancers JOIN——舞伴软删后为 null，
 * 前端回退「舞伴已下架」占位；dancerVisible = 未软删且 status=NORMAL（普通用户可跳详情
 * 的唯一口径——PENDING/HIDDEN/REJECTED 仅本人/管理员可见，见 DancerService 可见性规则）。
 * <p>
 * 隐私克制：需求记录只存枚举/id/整句文案（DemandRecord javadoc），列表同样不额外回填
 * 服务/时间结构化字段——message 已含全部需求信息，前端零拼接。
 * <p>
 * 2026-08-26 邀约中转（22 号文档）：行加 {@code status}（DemandStatus code；
 * NULL = 存量锚点记录，前端徽标兼容不渲染）——前端列表徽标区分
 * 等待回复/已同意/暂不方便/已自动发放/暂未回复。
 */
public record DemandRecordResponse(
        Long id,
        Long dancerId,
        String dancerNickname,
        String dancerAvatarUrl,
        String dancerCity,
        boolean dancerVisible,
        String message,
        String status,
        LocalDateTime createdAt) {
}
