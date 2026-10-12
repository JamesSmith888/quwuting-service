package org.quwuting.quwutingservice.timershare.dto.request;

/**
 * 给同行者点赞 / 取消赞（POST /timer-shares/{token}/likes，2026-10-12，V48）。
 * <p>
 * <b>目标用「角色 + 序号」指明，不用 userId</b>——本域不向对方下发 userId（54 号 §资料互看的边界：
 * 不给「跨会话串联同一个人」留通道），所以客户端只知道「主持方」或「第 N 位加入者」，
 * 与 {@code status.joins[].seq} 同一方案。包装类型：缺失显式拒绝，不猜默认值（没有「默认赞」这种东西）。
 *
 * @param relation 被赞的那位的角色：HOST（对方是出示二维码的人）/ JOINER（对方是扫码加入的人）；
 *                 经 {@code WireEnums.parse} 宽容解析（大小写 / 首尾空白）
 * @param seq      对方是加入者时 = 第几位（1 起）；对方是主持方时可缺省或 0
 * @param liked    true = 赞，false = 取消赞（幂等：本来就是该状态也算成功）
 */
public record SetTimerShareLikeRequest(String relation, Integer seq, Boolean liked) {
}
