package org.quwuting.quwutingservice.user.dto.response;

/**
 * 用户公开主页中的舞伴条目（2026-08-12 用户详情页：TA 创建的公开舞伴列表）。
 * <p>
 * 仅返回 status=NORMAL 的公开舞伴（隐私边界：PENDING/HIDDEN 资料不对公众展示，
 * 与舞伴列表页同可见性规则）；列表行展示 = 头像 + 昵称 + 城市（可选），
 * 点击跳转舞伴详情页。排序：最近创建在前（id 降序，后端稳定）。
 */
public record UserDancerResponse(
        Long id,
        String nickname,
        String avatarUrl,
        String city
) {}
