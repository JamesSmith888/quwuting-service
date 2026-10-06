package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 管理端「到访用户名单」行（2026-10-06，GET /admin/venues/{venueId}/visitors；
 * 文档 = docs/agents/52-venue-presence.md；仅 ADMIN）。
 * <p>
 * 定位：把门店列表行内的<b>聚合数字</b>下钻到<b>具体的人</b>——「这 5 个人是谁、
 * 来了几次、最近什么时候」。这是 admin 侧第一次在到访域暴露 userId 粒度，
 * 此前只有 {@link VenuePresenceStats} 的计数。
 * <p>
 * <b>用户字段为何全部由后端判定、前端零派生</b>（同 {@code AdminUserItem} 的纪律）：
 * 平台 98% 用户从未改过昵称（生产实测 873 个存活用户里 857 个昵称停在注册默认值），
 * 昵称没有辨认力；<b>{@code nicknameCustom} 是「列表主标题显示什么」的唯一判据</b>，
 * ⛔ 禁在 admin-web 另写一份「默认昵称」字面量（那会让两个端口径漂移）。
 * <p>
 * <b>隐私边界</b>：本行只含<b>公开资料 + 计数</b>；{@code distanceM} / {@code accuracyM} /
 * {@code writeBucket} 等原始痕迹字段<b>一律不下发</b>（用户经纬度在协议上不存在，
 * 距离是端侧自报值，见 {@code VenuePresencePing} 类注释）。名单要回答的是「谁来过」，
 * 不是「当时他站在哪个像素」。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}：全局策略是 {@code non_null}
 * （application.yaml），null 字段会被整个删掉，前端对 {@code undefined} 求值会出
 * {@code NaN} —— 35 号文档的教训，{@code AdminVenueListItem} 同款。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminVenueVisitorItem(
        Long userId,
        /**
         * 稳定用户代号（{@code U#00472}），由 id 派生、终身不变。与 {@link #userId}
         * 一一对应但不是 id：运营在沟通/工单/排查中念的是代号。
         */
        String userCode,
        /** 昵称（空 = 未设置；<b>是否显示由 {@link #nicknameCustom} 决定</b>，非本字段判空） */
        String nickname,
        /**
         * nickname 是否为用户自己起的（false = 仍是注册默认值或空）。
         * <b>前端据此决定主标题显示什么</b>：自定义昵称优先显示昵称、否则显示
         * {@link #userCode}——判据由后端权威下发，⛔ 禁前端再写一份默认昵称字面量。
         */
        boolean nicknameCustom,
        String avatarUrl,
        /**
         * 该用户在这家店的<b>到访次数</b>（窗口内去重到店日，同 V39 口径：
         * 每个 (谁, 哪天) 算一次，一天内去多次只记 1 次）。
         * <p>
         * ⚠️ 与「到访人数」是<b>不同口径且刻意不同</b>：次数按<b>自然日</b>去重，
         * 人数按<b>时间窗内最近命中时刻</b>去重（跨零点连场算 1 人）。同源计算、分别落列，
         * <b>禁止互相推算</b>（52 号 §4 统计口径节）。
         */
        long visitTimes,
        /**
         * 最近一次到访时刻（同口径、不设时间窗）；理论非空（名单行由到访证据生成），
         * 仍显式可空以防聚合形态变化后前端出 NaN。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastVisitAt,
        /**
         * 是否为内部账号（ADMIN 运营号或微信审核号，2026-10-06 名单页打标签用）。
         * <p>
         * <b>为什么展示而不排除</b>：admin 端只作展示用途，能看到全量原始记录更利于核查问题
         * （现网到访记录里 ADMIN 一人占约一半，不排除 = 名单第一行永远是平台自己人，
         * 运营会以为统计出错）。<b>与排序口径有意不同</b>——热度排序必须排除内部账号，
         * 否则平台自己人直接刷分（{@code VenuePresenceService#visitSharesForRanking}）。
         * 「展示要完整、排序要公平」是两个不同决策，不是同一口径的两种实现。
         */
        boolean internalAccount) {
}
