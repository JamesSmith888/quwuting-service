package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * 舞伴认可统计（详情页/列表页共用）。
 * 今日/7天/30天为 createdAt 滚动窗口（锚点"此刻"），recentDaily 按自然日（recognitionDate）
 * 聚合——"最近认可：昨天 +3 前天 +5"动态信息的语义载体（两套口径职责分离，见
 * {@code DancerRecognitionRepository#countByDay} 注释）。
 */
public record DancerRecognitionStats(
        long countAll,
        long countToday,
        long count7d,
        long count30d,
        /** 近7日每日认可数（按日倒序，最近在前），无数据时为空列表 */
        List<DailyRecognitionPoint> recentDaily
) {

    public record DailyRecognitionPoint(
            @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            long count
    ) {}
}
