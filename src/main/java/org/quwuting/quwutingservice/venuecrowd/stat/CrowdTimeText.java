package org.quwuting.quwutingservice.venuecrowd.stat;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 热度域相对时间措辞的唯一实现（2026-10-07 自 CrowdReportService 私有方法抽出，纯函数）：
 * 「刚刚 / N 分钟前 / N 小时前」。明细行、历史页、点赞名单共用，避免各写一份后措辞漂移。
 */
public final class CrowdTimeText {

    private CrowdTimeText() {
    }

    /** {@code at} 为 null 返回空串；{@code at} 晚于 {@code now}（时钟回拨 / 并发）按「刚刚」。 */
    public static String ageText(LocalDateTime at, LocalDateTime now) {
        if (at == null) {
            return "";
        }
        long minutes = Duration.between(at, now).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        return (minutes / 60) + " 小时前";
    }
}
