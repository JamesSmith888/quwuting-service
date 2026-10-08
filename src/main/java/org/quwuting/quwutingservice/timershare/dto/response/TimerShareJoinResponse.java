package org.quwuting.quwutingservice.timershare.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 扫码加入的响应（POST /timer-shares/{token}/join，2026-10-07，V42）。
 * <p>
 * 全局 Jackson 配置是 {@code default-property-inclusion: non_null}，null 字段会被<b>整个删掉</b>；
 * 这里 {@code snapshot} / {@code pausedAtServerMs} / {@code venue} 的 null 都带语义
 * （「没有快照」「没在暂停」「没有门店」），客户端若把 undefined 当成「字段漏了」就会误判，
 * 所以统一 ALWAYS 显式写出 null（同 {@code PresenceReportResponse} 的既有约定）。
 *
 * @param outcome      {@code TimerShareJoinOutcome} 的名字；JOINED / ALREADY_JOINED 才带 snapshot
 * @param serverNowMs  服务端当前时刻：接收方用它与自己测得的往返时间估算时钟偏移
 * @param snapshot     快照；非 JOINED / ALREADY_JOINED 时为 null
 * @param host         主持方资料（昵称 / 头像；2026-10-08，V45）——「双方互看」的接收方一侧；
 *                     非 JOINED / ALREADY_JOINED 时为 null；null 与「资料为空」用字段内层 null 区分
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TimerShareJoinResponse(String outcome, long serverNowMs, Snapshot snapshot,
                                     TimerShareProfileView host) {

    /**
     * 时间快照（服务端时间轴）。接收方还原本机计时：
     * {@code startTimeTs = startServerMs − offset}、{@code pausedAccumSeconds = excludedSeconds}、
     * {@code pausedAtTs = pausedAtServerMs − offset}（offset = 服务端时钟 − 本机时钟）。
     *
     * @param startServerMs     主持方真实起点
     * @param excludedSeconds   已排除出计费的整数秒
     * @param pausedAtServerMs  创建时主持方在暂停则 = 该时刻，否则 null（接收方据此以冻结态起步）
     * @param rule              计价参数（无名称；接收方本地派生展示名）
     * @param venue             主持方关联的门店（服务端据 id 取当前名称，不信任客户端传的名称）；无则 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(
            long startServerMs,
            int excludedSeconds,
            Long pausedAtServerMs,
            TimerShareRuleView rule,
            VenueView venue) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VenueView(long id, String name) {
    }

    /** 无快照的结果（EXPIRED / FULL / … 这些「预期的业务状态」） */
    public static TimerShareJoinResponse withoutSnapshot(String outcome, long serverNowMs) {
        return new TimerShareJoinResponse(outcome, serverNowMs, null, null);
    }
}
