package org.quwuting.quwutingservice.venuecrowd.dto.response;

import java.util.List;

/**
 * 常态人气（2026-10-07，GET /venues/{venueId}/crowd-reports/baseline，公开读）：
 * 回答「今晚比平时热闹还是冷清」——用户对绝对数字没有参照系（「100 人多不多」取决于这家店平时多少），
 * 基线就是参照系。
 * <p>
 * 文案<b>全部服务端权威派生</b>（样本量分档的措辞、区间、比较结论），前端零拼接零推导——
 * 规则调整（阈值 / 措辞）免发前端。统计口径见后端 docs/agents/53-venue-crowd-stats.md。
 *
 * @param windows       基线窗口（近 7 天 / 近 30 天，顺序即展示顺序；<b>前 N 个营业日、不含今晚</b>）
 * @param deviationText 今晚 vs 常态的比较结论（样本不够或无可比时 null，前端不渲染）
 * @param noteText      口径说明小字（恒下发）
 */
public record CrowdBaseline(
        List<Window> windows,
        String deviationText,
        String noteText
) {

    /**
     * 单个基线窗口。
     *
     * @param key        窗口标识（D7 / D30）
     * @param title      标题（近7天 / 近30天）
     * @param sampleTier 样本量分档（NONE / SPARSE / LIMITED / SOLID）——前端据此决定是否弱化，不据此拼文案
     * @param voterCount 独立人数（一人一票之后）
     * @param valueText  主值（SOLID/LIMITED = 中位档；SPARSE = 原值列表；NONE = null）
     * @param rangeText  区间说明（LIMITED = 最低~最高；SOLID = 中间一半；无差异或样本不足 = null）
     * @param subText    样本量说明（NONE = null）
     * @param inviteText NONE 时的邀请文案（其余 null）
     */
    public record Window(
            String key,
            String title,
            String sampleTier,
            int voterCount,
            String valueText,
            String rangeText,
            String subText,
            String inviteText
    ) {
    }
}
