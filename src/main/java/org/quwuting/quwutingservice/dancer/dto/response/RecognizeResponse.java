package org.quwuting.quwutingservice.dancer.dto.response;

import java.util.List;

/**
 * 认可 toggle 的即时响应（2026-08-15 扩展：前端据此绝对收敛，无需整页刷新）。
 * <ul>
 *   <li>recognized = 服务端确认后的最终参与态（true=今日已认可，false=今日已取消）；</li>
 *   <li>replacedFrom = 换票时被替换掉的旧标签（今日同标签取消 / 首次参与 / 旧列表取消
 *       时为 null；旧多标签记录换票时为 null——多标签无法以单值表达，前端以
 *       tags 绝对快照收敛）；</li>
 *   <li>myTags = 换票后今日认可携带的标签（单票模型下为 [新标签]；取消后为 []），
 *       详情页 chip 活跃态数据源；</li>
 *   <li>stats = 最新四窗口认可统计（每日一记模型下服务端确认后即真实值）；</li>
 *   <li>tags = 标签聚合绝对快照（同 GET /dancers/{id}/tags 口径），前端 chip 计数
 *       直接覆盖本地——乐观 ±1 与真实值偏差（旧多标签数据等边界）由快照幂等收敛。</li>
 * </ul>
 */
public record RecognizeResponse(
        boolean recognized,
        String replacedFrom,
        List<String> myTags,
        DancerRecognitionStats stats,
        List<DancerTagStat> tags
) {}
