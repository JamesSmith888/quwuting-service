package org.quwuting.quwutingservice.venuecrowd.dto.response;

/**
 * 门店热度聚合摘要（2026-08-29，公开读；详情页「今晚热度」区块数据源）。
 * <p>
 * 展示文案由服务端权威派生（levelName/levelHint/tierTag/mainText/maleText/
 * ageText/emptyText），前端仅渲染零拼接——对齐项目「label 服务端权威」契约。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@code hasData}：窗口内是否有上报（false → 前端渲染空态 emptyText）；</li>
 *   <li>{@code female}/{@code male}：主/次信号众数视图（无对应数据时 null）；
 *       {@code share} = 众数档位权重占比（可信度加权，见 CrowdReportService）；</li>
 *   <li>{@code tier}/{@code tierText}：置信度分层（CrowdTier code + 完整胶囊文案，
 *       如「舞友报告 · 未经核实」「资深舞友报告」「多人报过」「说法不一」）；</li>
 *   <li>{@code mainText}：主信号完整展示文案（如「舞伴 不错（约100）· 3 位舞友 · 1 小时前」）；</li>
 *   <li>{@code maleText}：次信号展示文案（如「男客 正常 · 2 人」，无数据 null）；</li>
 *   <li>{@code ageText}：最新上报相对时间（「刚刚 / N 分钟前 / N 小时前」）；</li>
 *   <li>{@code mine}：我今天的上报（未登录或未上报 null——前端据此渲染「报一下 / 已上报·可改」）。</li>
 * </ul>
 */
public record CrowdSummary(
        boolean hasData,
        CrowdLevelView female,
        CrowdLevelView male,
        int reporterCount,
        String tier,
        String tierText,
        String mainText,
        String maleText,
        String ageText,
        String emptyText,
        CrowdMineView mine
) {

    /** 单维度众数视图 */
    public record CrowdLevelView(
            int level,
            String levelName,
            String levelHint,
            int count,
            double share
    ) {
    }

    /** 我今天的上报（可改） */
    public record CrowdMineView(
            int femaleLevel,
            Integer maleLevel,
            String femaleLevelName
    ) {
    }
}
