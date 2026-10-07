package org.quwuting.quwutingservice.venuecrowd.enums;

/**
 * 热度聚合置信度分层（2026-08-29，沿用门店报告 resolveAnnouncementTier 三级
 * 哲学：众包信号视觉权重必须与置信度匹配，禁冒充平台权威）。
 * <p>
 * 判定规则（<b>唯一实现 = {@code CrowdConsensus#tierOf}</b>，2026-10-07 起口径为「一人一票 +
 * 中位数 ±1 档一致性」，N = 窗口内独立投票人数，share = 落在中位数 ±1 档内的权重占比，
 * 权重仅在 N≥5 时启用且封顶）：
 * <ul>
 *   <li>EMPTY：N == 0（空态，前端引导「来报第一个」）；</li>
 *   <li>UNVERIFIED：N == 1（单条中性降级「舞友报告 · 未经核实」）；
 *       上报者贡献权重 ≥ VETERAN_WEIGHT 时升级 UNVERIFIED_VETERAN
 *       （「资深舞友报告」，同样未经核实——信任度来自历史贡献而非本条验证）；</li>
 *   <li>UNVERIFIED：N == 2 且 share ≥ CONFIRM_SHARE（两人一致，中性呈现）；</li>
 *   <li>CONFIRMED：N ≥ 3 且 share ≥ CONFIRM_SHARE（多人报过，正常呈现）；</li>
 *   <li>CONFLICT：N ≥ 2 且 share < CONFIRM_SHARE（说法不一，不站队）。</li>
 * </ul>
 * 阈值常量唯一出处 {@code CrowdPolicy}；本枚举只承载<b>展示文案</b>，不承载判定。
 */
public enum CrowdTier {
    EMPTY(""),
    UNVERIFIED("舞友报告 · 未经核实"),
    UNVERIFIED_VETERAN("资深舞友报告 · 未经核实"),
    CONFIRMED("多人报过"),
    CONFLICT("说法不一");

    /** 完整胶囊文案（前端仅渲染，零拼接） */
    private final String text;

    CrowdTier(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
