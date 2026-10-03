package org.quwuting.quwutingservice.venuepresence.enums;

/**
 * 到店足迹开关状态的「确立来源」（2026-09-29 四轮 V34；2026-10-03 五轮改「到店首问」）。
 * <p>
 * 判据 = 「这次状态确立是谁做的」：
 * <ul>
 *   <li>{@link #PROMPT}（2026-10-03 新增）= 用户在<b>第一次真正到店</b>时回答了首问弹窗
 *       （允许 / 不用了）；</li>
 *   <li>{@link #USER} = 用户在「我的-设置-到店足迹」手动拨动开关；</li>
 *   <li>{@link #DEFAULT} = <b>历史来源，2026-10-03 起不再写入</b>：09-29 ~ 10-03「默认开启」
 *       模型下，采集首次触达用户时由服务端补记的出厂态（enabled 恒 true，<b>用户从未被询问</b>）。</li>
 * </ul>
 * <b>为什么区分「显式」与「非显式」</b>（{@link #isExplicit()}）：行踪轨迹属敏感个人信息，
 * 处理需要用户单独同意（个保法第 29 条）；DEFAULT 行恰恰记录的是「没问过」——它是证据链里的
 * <b>缺口</b>而不是同意。服务端采集门禁（{@code VenuePresenceService#report}）与 admin 统计
 * 都以「最新一条是显式来源且 enabled」为同一判据：DEFAULT 用户在下次到店被补问前一律不收数据。
 */
public enum ConsentSource {

    /** 历史：默认态确立（09-29 ~ 10-03 首次采集 ping 时补记，enabled 恒 true；已停止写入） */
    DEFAULT("默认开启（未经询问）"),

    /** 用户手动变更（「我的-设置-到店足迹」拨动开关） */
    USER("用户设置"),

    /** 到店首问回答（第一次真正到店时的「允许 / 不用了」弹窗，2026-10-03） */
    PROMPT("到店询问");

    private final String displayName;

    ConsentSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否为用户本人做出的显式决定（USER / PROMPT）。DEFAULT 是「系统替用户默认」，
     * 不构成同意——采集门禁与 admin「已允许」口径共用本判据（单点）。
     */
    public boolean isExplicit() {
        return this != DEFAULT;
    }
}
