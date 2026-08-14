package org.quwuting.quwutingservice.venuereaction.dto.response;

/**
 * Reaction toggle 结果（2026-08-14 每日一票契约扩展）：
 * <ul>
 *   <li>{@code reacted}：操作后该 code 是否已参与（true=参与/换票成功，false=已取消）；</li>
 *   <li>{@code replacedFrom}：当日被替换掉的旧 code——仅"每日一票"模式的<b>换票</b>路径
 *       非空（取消 / 首次参与 / 多选模式（开关关闭）均为 null）。前端据此把旧 code 的
 *       本地参与态同步为 false（乐观换票的幂等 reconcile，见前端 services/reaction.ts）。</li>
 * </ul>
 */
public record ToggleReactionResult(boolean reacted, String replacedFrom) {
}
