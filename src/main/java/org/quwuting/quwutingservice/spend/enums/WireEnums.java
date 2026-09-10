package org.quwuting.quwutingservice.spend.enums;

import java.util.Locale;

/**
 * 消费账本枚举的**协议字面量解析**（唯一入口）。
 *
 * ── 为什么需要它（2026-09-11 闪屏归零事故，44 号 §21）──
 * 客户端历史上行的是本地领域字面量（小写 {@code "dance" / "manual"}），而服务端
 * 枚举常量名是全大写，旧实现直接 {@code SpendSource.valueOf(item.source())} ——
 * 抛 {@link IllegalArgumentException} ⇒ {@code validate()} 判非法 ⇒ **每一条账目
 * 100% 被拒**，而客户端把 rejected 当已处理清队、状态行仍显示"已同步"。生产库
 * {@code qwt_spend_entries} 因此长期 0 行，直至用户报"账本数字 0.1 秒后归零"。
 *
 * ── 为什么"宽容读"是必须的（而不是只修客户端）──
 * 客户端版本与服务端**必然存在时间差**：老版本小程序仍在小写上报（且没有任何机制
 * 强制用户升级）。只修客户端 = 存量用户永远上不了云，与"最终百分百成功"直接冲突。
 * 归一化必须在**服务端**做，因为服务端是唯一能被双方即时收敛的汇聚点。
 *
 * ── 判据（禁混淆）──
 * 宽容的只有**大小写与首尾空白**（同一枚举名的书写变体，无语义歧义）；取值本身
 * 非法（未知枚举名 / null）一律返回 {@code null}，由调用方判为非法条目——
 * **不做任何"猜一个默认值"的兜底**，因为 source 决定"场次"口径，猜错等于伪造统计。
 */
public final class WireEnums {

    private WireEnums() {
    }

    /**
     * 解析协议字面量：trim + 大写后按枚举名匹配；无法识别返回 {@code null}。
     *
     * @param type 目标枚举
     * @param raw  客户端上报的原始字面量（可空）
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
