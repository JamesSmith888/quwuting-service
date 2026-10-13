package org.quwuting.quwutingservice.payqr.enums;

/**
 * 用户「计时收款码配置」的三态（2026-10-13 同日第三次修；文档 = quwuting 仓 docs/agents/60-pay-qr.md 决策 14）。
 * <p>
 * <b>为什么不是「张数 &gt; 0 / 否」两态</b>：首版 admin 只用 {@code payQrCount > 0} 决定“挂不挂标签”，
 * 于是「没配置」「还没升级到带上报的小程序」「上报失败」三种截然不同的事实全部坍缩成“什么都不显示”——
 * 带上报的新版小程序还没发布时，运营在用户详情页找不到任何“收款”字样，无法判断是功能没做还是这个人没配。
 * 状态与展示文案由服务端单点判定下发，前端零字典（同 {@code PresenceConsentState}）。
 * <p>
 * <b>NO_REPORT 的语义边界</b>：客户端对“从未配置过且本机 0 张”的用户<b>不上报</b>（避免为没配置的人每人写一行 0），
 * 所以“无记录”= 没配置过，<b>或</b>还在用不带上报的旧版小程序，两者服务端无法区分——文案必须如实写出这两种可能，
 * 不得读成“未配置”。
 */
public enum PayQrState {

    /** 本机当前持有 ≥ 1 张 */
    CONFIGURED,

    /** 配置过、现已清空（有首次配置时刻，当前 0 张） */
    CLEARED,

    /** 无上报记录（没配置过 / 旧版小程序未上报，二者不可区分） */
    NO_REPORT;

    /** 判据单点：当前张数 + 是否曾配置过（first_configured_at 非空） */
    public static PayQrState of(int count, boolean everConfigured) {
        if (count > 0) {
            return CONFIGURED;
        }
        return everConfigured ? CLEARED : NO_REPORT;
    }

    /** 展示文案（服务端判定；CONFIGURED 带张数） */
    public String display(int count) {
        return switch (this) {
            case CONFIGURED -> "已配置 " + count + " 张";
            case CLEARED -> "曾配置，现已清空";
            case NO_REPORT -> "无上报记录（没配置过，或仍在用不带收款码上报的旧版小程序）";
        };
    }
}
