package org.quwuting.quwutingservice.venuepresence.enums;

/**
 * 到店足迹开关状态的「确立来源」（2026-09-29 四轮，V34）。
 * <p>
 * 判据 = 「这次状态确立是谁做的」：DEFAULT = 无人动作，采集首次触达该用户时
 * 补记的出厂态（enabled=true）；USER = 用户在「我的-设置」手动拨动开关。
 * 区分二者的统计价值：最新态仍是 DEFAULT 的用户 = 「从未改过设置的默认开启者」，
 * 是评估首问撤销、开关曝光价值的基础分母。
 */
public enum ConsentSource {

    /** 默认态确立（第一次采集 ping 时补记，enabled 恒 true） */
    DEFAULT("默认开启"),

    /** 用户手动变更（「我的-设置-到店足迹」拨动开关） */
    USER("用户设置");

    private final String displayName;

    ConsentSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
