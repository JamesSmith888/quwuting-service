package org.quwuting.quwutingservice.points.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 礼物字典（2026-08-12 礼物赠送系统：积分赠送 → 礼物赠送的载体字典）。
 * <p>
 * 背景（根因，见后端 AGENTS.md「积分系统 · 礼物赠送」）：直接赠送积分 = 资产转移
 * 语义（delta&lt;0 从 A 到 B），触碰"可流转准货币"合规红线且无情感载体；礼物方案下
 * 积分是不可转移的个人资产，只能购买礼物（消费），礼物送出即一次性表达——彻底切断
 * 资产转移链条。本枚举承载"送什么"语义：一次赠送 = 一个礼物（price 积分）。
 * <p>
 * 字段约束（与 ReactionCode 同模式，见 V9/V13）：
 * <ul>
 *   <li>code = 枚举名，{@code gift_code} 列 varchar(30)（V13 新增，仅赠送流水非空）；</li>
 *   <li>{@code emoji} 字符与前端 {@code constants/gifts.ts} 的 src 一一对应——emoji 是
 *       后端契约也是图片加载失败时的兜底；</li>
 *   <li>{@code price} = 所需积分（唯一事实源在本枚举，前端镜像展示——禁止单独维护
 *       "展示价"，防展示价 ≠ 实扣价）；价格梯度设计：1-5 分，覆盖日常小礼物（1分）
 *       到最珍重心意（5分=单目标每日上限），详见 docs/consumption-model.md。</li>
 * </ul>
 * 扩展礼物：改本枚举 + 前端 constants/gifts.ts 镜像 + 资源（scripts/fetch-gift-assets.py
 * 清单追加，重跑补 png）——三处同步，流程与 Reaction 字典一致。
 */
public enum GiftCatalog {
    TEDDY_BEAR("🧸", "小熊", 1),
    ROSE("🌹", "玫瑰花", 2),
    GIFT_BOX("🎁", "礼物盒", 3),
    HEART("❤", "爱心", 5),
    CAKE("🎂", "蛋糕", 3),
    STAR("⭐", "星星", 1),
    CANDY("🍬", "糖果", 1),
    KITTY("🐱", "小猫", 3),
    PUPPY("🐶", "小狗", 3),
    BALLOON("🎈", "气球", 2),
    RAINBOW("🌈", "彩虹", 4),
    SPARKLE("✨", "闪闪", 1);

    private final String emoji;
    private final String name;
    private final int price;

    GiftCatalog(String emoji, String name, int price) {
        this.emoji = emoji;
        this.name = name;
        this.price = price;
    }

    public String emoji() {
        return emoji;
    }

    /** 礼物中文名（后端唯一事实源，前端镜像展示）——不可命名 name()：Enum.name() 是 final */
    public String displayName() {
        return name;
    }

    /** 所需积分（唯一事实源在本枚举——防"展示价 ≠ 实扣价"不一致） */
    public int price() {
        return price;
    }

    /** 按 code 名解析（未知 code → empty，由 Service 抛业务错误，禁直接 valueOf 抛 500） */
    public static Optional<GiftCatalog> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(g -> g.name().equals(code))
                .findFirst();
    }
}
