package org.quwuting.quwutingservice.venue.dailyopening.enums;

/**
 * 外部舞讯通道被门禁跳过的原因（2026-09-14，V25；方案见 docs/agents/48）。
 * <p>
 * 跳过不是失败，是「尊重人工判断」——两个原因对应两种不同性质的人工例外：
 * <ul>
 *   <li>{@link #LOCKED} 时间维度的临时优先：人工刚改过状态，锁内不让外部推断翻案；</li>
 *   <li>{@link #EXEMPT} 事实维度的永久例外：人工声明本店不参与舞讯白名单推断
 *       （该店不在舞讯覆盖范围 / 被系统性漏报）。</li>
 * </ul>
 * 汇报时必须逐类显式说明，否则用户会以为「该暂停的没暂停」是漏跑。
 */
public enum GuardSkipReason {

    /** 人工锁未过期：本店状态是人工判断，且仍在保护窗口内。 */
    LOCKED("人工锁定中"),

    /** 已豁免：人工声明本店不参与舞讯白名单/未上榜差集推断。 */
    EXEMPT("已豁免舞讯推断");

    private final String displayName;

    GuardSkipReason(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
