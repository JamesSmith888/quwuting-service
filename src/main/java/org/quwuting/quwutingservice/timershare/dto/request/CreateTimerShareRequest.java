package org.quwuting.quwutingservice.timershare.dto.request;

import java.util.List;

/**
 * 创建 / 刷新计时分享会话（POST /timer-shares，2026-10-07，V42；文档 = docs/agents/54-timer-share.md）。
 * <p>
 * <b>全部字段用包装类型</b>：缺失（null）必须能与 0 / false 区分，才能被显式拒绝而不是被静默当成
 * 「0 秒 / 暂停」——写侧盲收会把客户端 bug 放大成脏数据（账本域 40 号判据 1 的同款教训）。
 * <p>
 * 请求体只承载<b>读数与计价参数</b>，没有任何用户输入的自由文本（规则名不上云，见 V42 头注）。
 *
 * @param sessionKey         主持方端的场次标识：起点毫秒[:成员id]（≤48 字符，数字字母 : _ -）。
 *                           与登录态 userId 共同唯一 ⇒ 同一场重复打开弹层 = 刷新同一行
 * @param wallElapsedMs      此刻 − 该成员的真实起点（毫秒，含暂停；0 ~ 12h）
 * @param netElapsedSeconds  屏幕上的净已计秒数（floor，已扣暂停与去掉的空窗）
 * @param running            此刻是否在走（false = 暂停中）
 * @param rule               计价参数
 * @param venueId            主持方关联的门店（可空；服务端校验存在，不存在则忽略而不是报错）
 * @param parentToken        主持方自己是经哪张码加入的（可空；用于传播链，格式非法则忽略）
 */
public record CreateTimerShareRequest(
        String sessionKey,
        Long wallElapsedMs,
        Integer netElapsedSeconds,
        Boolean running,
        RuleInput rule,
        Long venueId,
        String parentToken) {

    /** 计价规则（只有档位；没有名称） */
    public record RuleInput(List<TierInput> tiers) {
    }

    /**
     * 一档价格。
     *
     * @param durationMinutes 一个计费单元的分钟数（可带小数，如 3.5）
     * @param price           该单元的价格（元）
     * @param mode            计费模式：null / "step" = 档位式（缺省），"linear" = 折算式
     */
    public record TierInput(Double durationMinutes, Double price, String mode) {
    }
}
