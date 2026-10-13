package org.quwuting.quwutingservice.timershare.dto.response;

import java.util.List;

/**
 * 点赞状态（GET /timer-shares/{token}/likes，2026-10-12，V48）：调用者在这张会话里「面前的每一位」——
 * 我赞了没有、他赞了我没有、此刻还赞得了吗。
 * <p>
 * <b>为什么是独立资源而不是塞进 {@code status} / {@code peer}</b>（54 号 §十五的设计登记）：那两个接口被
 * 对账轮询按「成本边界」驱动，且<b>结算后就停读</b>——而点赞恰恰大量发生在结算之后。把点赞状态塞进去，要么读不到、
 * 要么放宽轮询边界（那是 56 号页面进入成本契约的约束）。独立资源 = 详情卡打开时按需读一次，空闲成本为零。
 * （2026-10-13：前端对账轮询另外顺带读本接口，只在「我还在计时、码上有过人」时、节流读取——见前端 61 号 §十；
 * {@code status} / {@code peer} 契约仍无点赞字段。）
 * <p>
 * 调用者不是成员 / token 非法 → {@code enabled} 照常、{@code items} 为空（不报错，不泄露 token 是否存在）。
 *
 * @param enabled     运营开关当前是否开启；false 时 {@code items} 恒空，客户端整个隐藏点赞入口
 * @param items       调用者面前的可赞对象（主持方视角 = 每位加入者；加入者视角 = 主持方）
 * @param serverNowMs 服务端当前时刻
 */
public record TimerShareLikesResponse(boolean enabled, List<Item> items, long serverNowMs) {

    /**
     * @param relation  对方的角色（HOST / JOINER）
     * @param seq       对方是加入者时 = 第几位（1 起，= {@code status.joins[].seq}）；对方是主持方 = 0
     * @param likedByMe 我已赞对方
     * @param likesMe   对方已赞我
     * @param likeable  此刻我还能对这位赞 / 取消赞（窗口内）。false 且 {@code likedByMe} 时客户端只展示「已赞」
     */
    public record Item(String relation, int seq, boolean likedByMe, boolean likesMe, boolean likeable) {
    }
}
