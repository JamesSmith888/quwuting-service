package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 点赞记录（GET /timer-shares/likes/history，2026-10-13 第二版；文档 = docs/agents/55-timer-share-likes.md §八）：
 * <b>记录主人</b>（{@code subject}）在所有一同计时的会话里「收到的赞」与「送出的赞」，按对方分组。
 * <p>
 * 主人是谁由请求决定：不带焦点参数 = 调用者本人；带「会话凭据 + 角色 + 序号」= 调用者在这张会话里点开的那位同行者
 * （用户 2026-10-13 明确要求：点头像就能看到<b>该用户</b>所有的点赞记录——所以同一张会话里的对家看得到彼此的记录）。
 * <p>
 * 🚫 红线（55 号 §一，已按用户口径修订）：赞仍然<b>无经济价值、永不进算法</b>——不给积分、不进榜、不排序、不改任何统计口径；
 * 记录只是一份「谁赞过谁」的时间线，<b>没有</b>全站聚合量，也没有任何以被赞者为键的索引。
 * 不下发任何用户 id：一组 = 一位对方，用「现在的昵称 / 头像」认人（与会话内「资料互看」同口径）。
 *
 * @param enabled     运营开关当前是否开启；false 时其余恒空，客户端整个隐藏入口（同 {@link TimerShareLikesResponse}）
 * @param subject     这是谁的记录（{@code TimerShareLikeSubject} 常量名）；{@code UNRESOLVED} = 请求指明了对方却解析不出，
 *                    其余恒空——客户端据此不画入口，且<b>用它校验</b>返回的确是它要的那一份（防张冠李戴）
 * @param summary     总览（受 {@code truncated} 影响时是「所取范围内」的数）
 * @param groups      按对方分组：调用者自己那组（{@code me}，仅 {@code PEER} 视角才可能有）在最前，其余按最近一次点赞时间倒序
 * @param truncated   取数撞到上限（会话数 / 行数 / 分组数）——客户端据此说明「只显示最近的」，不假装完整
 * @param serverNowMs 服务端当前时刻
 */
public record TimerShareLikeHistoryResponse(
        boolean enabled,
        String subject,
        Summary summary,
        List<Group> groups,
        boolean truncated,
        long serverNowMs) {

    /**
     * @param received 主人收到的赞（当前仍有效的，取消过的不计）
     * @param given    主人送出的赞
     * @param people   涉及的对方人数
     */
    public record Summary(int received, int given, int people) {
    }

    /**
     * 主人面前的一位对方。
     *
     * @param me        这位对方就是调用者本人（{@code PEER} 视角下「我和 TA 之间」那一组；{@code SELF} 视角恒 false）
     * @param nickname  对方现在的昵称（null = 未设置 / 账号已不可查，客户端兜底占位）
     * @param avatarUrl 对方现在的头像（null = 未设置 / 不可查）
     * @param received  这位赞过主人的次数（= 有效行数；每场会话至多 1 次）
     * @param given     主人赞过这位的次数
     * @param lastAtMs  最近一次点赞的服务端时刻
     * @param events    最近的若干条（新→旧），条数有上限；总数以 received / given 为准
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Group(
            boolean me,
            String nickname,
            String avatarUrl,
            int received,
            int given,
            long lastAtMs,
            List<Event> events) {
    }

    /**
     * @param direction {@code TimerShareLikeDirection} 常量名（相对记录主人）
     * @param atMs      这次点赞的服务端时刻
     */
    public record Event(String direction, long atMs) {
    }
}
