package org.quwuting.quwutingservice.bulletin.dto.response;

/**
 * 快讯表态 toggle 结果。
 *
 * @param reacted      操作后该 code 是否已表态（true = 参与/换票成功，false = 已取消）
 * @param replacedFrom 被替换掉的旧 code——仅"换票"路径非空（首次参与 / 取消均为 null）。
 *                     前端据此把旧 code 的本地高亮与计数同步回退（乐观换票的幂等 reconcile）。
 */
public record BulletinReactionToggleResult(boolean reacted, String replacedFrom) {
}
