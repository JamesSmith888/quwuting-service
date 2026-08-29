package org.quwuting.quwutingservice.venuecrowd.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端门店热度聚合（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 管理端第四 tab「热度上报」数据源：按店聚合最近 24h 上报（运营视角看异常——
 * 档位分布是否打架（conflict）、是否有高频修改用户（刷量嫌疑）、上报量是否异常）。
 * 展示文案 levelName/levelHint 后端权威；nickname 批量回填（未取到显示 userId）。
 */
public record AdminCrowdReportSummary(
        Long venueId,
        String venueName,
        /** 最近 24h 上报条数（含同日修改的 UPDATE，非独立人数） */
        int reportCount24h,
        /** 最近 24h 在店舞伴档位分布（按条数，降序；众数占比 < 0.6 → conflict） */
        List<LevelCount> femaleDistribution,
        /** 最近 24h 男客密度分布（按条数，降序；无男客上报时空列表） */
        List<LevelCount> maleDistribution,
        /** 说法不一（众数条数占比 < 0.6）——运营需关注的「各执一词」门店 */
        boolean conflict,
        /** 同日修改 ≥ 3 次的用户（刷量/反复改嫌疑，运营核实） */
        List<HighModifyUser> highModifyUsers,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime latestAt
) {

    /** 单档位计数（levelName/levelHint 后端权威，前端零拼接） */
    public record LevelCount(
            int level,
            String levelName,
            String levelHint,
            long count
    ) {
    }

    /** 高频修改用户（昵称批量回填，取不到回退 userId） */
    public record HighModifyUser(
            Long userId,
            String nickname,
            int modifyCount
    ) {
    }
}
