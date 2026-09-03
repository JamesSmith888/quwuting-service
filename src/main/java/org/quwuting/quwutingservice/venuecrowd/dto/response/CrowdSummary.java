package org.quwuting.quwutingservice.venuecrowd.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店热度聚合摘要（2026-08-29，公开读；详情页「今晚热度」区块数据源）。
 * <p>
 * 展示文案由服务端权威派生（levelName/levelHint/tierTag/mainText/maleText/
 * ageText/emptyText），前端仅渲染零拼接——对齐项目「label 服务端权威」契约。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@code hasData}：6h 窗口内是否有上报（false → 前端渲染空态 emptyText；
 *       历史数据不在此——2026-08-29 定版：过期/历史记录走独立历史页
 *       {@code GET /venues/{id}/crowd-reports/history}，详情页右下角链接进入）；</li>
 *   <li>{@code female}/{@code male}：主/次信号众数视图（无对应数据时 null）；
 *       {@code share} = 众数档位权重占比（可信度加权，见 CrowdReportService）；</li>
 *   <li>{@code tier}/{@code tierText}：置信度分层（CrowdTier code + 完整胶囊文案，
 *       如「舞友报告 · 未经核实」「资深舞友报告」「多人报过」「说法不一」）；</li>
 *   <li>{@code mainText}：主信号完整展示文案（如「舞伴 不错（约100）· 3 位舞友 · 1 小时前」）；</li>
 *   <li>{@code maleText}：次信号展示文案（如「男客 一般（约50）· 2 人」，无数据 null）；</li>
 *   <li>{@code ageText}：最新上报相对时间（「刚刚 / N 分钟前 / N 小时前」）；</li>
 *   <li>{@code mine}：我今天的上报（未登录或未上报 null——前端据此渲染「报一下 / 已上报·可改」）；</li>
 *   <li>{@code rows}：每个用户的上报明细（2026-08-29 用户要求「表格式列表展示每个用户
 *       上报」；createdAt 倒序；**2026-09-03 用户要求详情页直接展示用户头像 + 名称
 *       （超长省略）**——badgeText 服务端权威三档（权重 ≥ VETERAN_WEIGHT 资深 /
 *       ≥ REGULAR_WEIGHT 常客 / 普通，行内与昵称并列）；avatarUrl = 上报者头像
 *       （空则前端首字占位）；isMine = 是否本人（登录态回填，前端高亮 +「我」标记）；
 *       nickname = 完整昵称（空昵称兜底「匿名」）；male 未报时 maleLevelName 为 null；
 *       **仅含 6h 窗口内有效行**——历史/过期记录走 {@code CrowdHistoryRow} 历史页）。</li>
 *   <li>{@code rewardText}/{@code upgradedBadgeText}：<b>仅 POST 提交响应填充</b>的
 *       即时反馈（GET 恒 null）——rewardText = 本次提交新触发「确认后积分」的服务端
 *       权威文案（如「你的上报被 3 位舞友确认 · +3 积分已到账」）；upgradedBadgeText =
 *       本次提交后身份升级文案（普通→常客→资深，如「身份升级：常客舞友」）；
 *       两者为反馈闭环的即时确认（对齐「label 服务端权威」契约，前端零拼接）。</li>
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
        List<CrowdReportRow> rows,
        String rewardText,
        String upgradedBadgeText
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

    /**
     * 单个用户的上报明细（详情页「今晚热度」表格式列表行，2026-08-29；2026-09-03
     * 用户要求表格直接展示<b>头像 + 名称（超长省略）</b>）。
     */
    public record CrowdReportRow(
            Long userId,
            /** 用户标识（服务端权威三档：资深 / 常客 / 普通） */
            String badgeText,
            /** 完整昵称（2026-09-03 详情页表格直接展示；空兜底「匿名」） */
            String nickname,
            /** 上报者头像 URL（2026-09-03；空 = 用户未设头像，前端渲染首字占位） */
            String avatarUrl,
            /** 是否本人（2026-09-03；登录态回填——前端高亮自己的行 +「我」标记） */
            boolean isMine,
            String femaleLevelName,
            String femaleLevelHint,
            String maleLevelName,
            String maleLevelHint,
            String ageText
    ) {
    }

    /**
     * 全部热度历史行（2026-08-29 用户需求「用户可以看到过期后的记录」最终形态：
     * 独立历史页数据源，GET /venues/{id}/crowd-reports/history，分页全量）。
     * <p>
     * 字段全部服务端权威派生（badgeText 三档 / 档位名+锚点 / ageText 相对时间 /
     * reportAt 绝对时间 yyyy-MM-dd HH:mm:ss / expired 窗口外标记——前端仅据此
     * 派生「已过期」标签 + 置灰样式，零拼接）。
     */
    public record CrowdHistoryRow(
            Long id,
            Long userId,
            /** 用户标识（资深 / 常客 / 普通） */
            String badgeText,
            /** 完整昵称（空兜底「匿名」） */
            String nickname,
            /** 上报者头像 URL（2026-09-03；空 = 用户未设头像，前端渲染首字占位） */
            String avatarUrl,
            /** 是否本人（2026-09-03；登录态回填——前端高亮自己的行 +「我」标记） */
            boolean isMine,
            String femaleLevelName,
            String femaleLevelHint,
            String maleLevelName,
            String maleLevelHint,
            /** 上报绝对时间（yyyy-MM-dd HH:mm:ss，前端 formatGiftTime 展示「今天/昨天 HH:mm」） */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime reportAt,
            /** 相对时间（「刚刚 / N 分钟前 / N 小时前」） */
            String ageText,
            /** 是否已出 6h 有效窗口（true = 历史参考，前端置灰 +「已过期」） */
            boolean expired
    ) {
    }
}
