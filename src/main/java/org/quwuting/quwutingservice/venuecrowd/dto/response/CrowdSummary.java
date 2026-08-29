package org.quwuting.quwutingservice.venuecrowd.dto.response;

import java.util.List;

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
 *   <li>{@code maleText}：次信号展示文案（如「男客 一般（约50）· 2 人」，无数据 null）；</li>
 *   <li>{@code ageText}：最新上报相对时间（「刚刚 / N 分钟前 / N 小时前」）；</li>
 *   <li>{@code mine}：我今天的上报（未登录或未上报 null——前端据此渲染「报一下 / 已上报·可改」）；</li>
 *   <li>{@code rows}：每个用户的上报明细（2026-08-29 用户要求「表格式列表展示每个用户
 *       上报」；createdAt 倒序；**列表行不展示用户名**——badgeText 服务端权威三档
 *       （权重 ≥ VETERAN_WEIGHT 资深 / ≥ REGULAR_WEIGHT 常客 / 普通，表头已有「舞友」
 *       列名故行内不带「舞友」后缀）；nickname = **完整昵称，仅详情弹层展示**（纯
 *       展示不可点击，空昵称兜底「匿名」）；male 未报时 maleLevelName 为 null）。</li>
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
        CrowdMineView mine,
        List<CrowdReportRow> rows
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

    /** 单个用户的上报明细（详情页「今晚热度」表格式列表行，2026-08-29） */
    public record CrowdReportRow(
            Long userId,
            /** 用户标识（服务端权威三档：资深 / 常客 / 普通；表头已有「舞友」列名，行内不带后缀） */
            String badgeText,
            /**
             * 完整昵称（2026-08-29 用户拍板：详情弹层**直接展示完整昵称**，纯展示
             * 不可点击跳转；列表行仍不显示昵称——公共面不点名，昵称仅详情补充；
             * 空昵称兜底「匿名」）。
             */
            String nickname,
            String femaleLevelName,
            String femaleLevelHint,
            String maleLevelName,
            String maleLevelHint,
            String ageText
    ) {
    }
}
