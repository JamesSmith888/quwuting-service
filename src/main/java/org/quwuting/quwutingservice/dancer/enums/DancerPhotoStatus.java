package org.quwuting.quwutingservice.dancer.enums;

/**
 * 舞伴相册照片状态（照片级"先审后发"审核闸门，与 DancerStatus 的资料审核互补）。
 * <ul>
 *   <li>{@link #PENDING}：待审核——舞伴本人（createdBy）上传后的初始状态，不对外展示，
 *       仅舞伴本人与平台管理员可见；</li>
 *   <li>{@link #PUBLIC}：公开——管理员审核通过后随舞伴主页公开展示（仅舞伴 NORMAL 时）；</li>
 *   <li>{@link #REJECTED}：已驳回——管理员审核未通过（照片不实/违规等），不对外展示，
 *       舞伴本人在编辑页可见状态并可删除重传。</li>
 * </ul>
 * 设计根因（AGENTS.md「舞伴生态体系 · 相册与照片审核」）：舞伴是真实个人，照片是其真实
 * 影像、风险高于场所照片——必须逐张人审后才公开（与资料 PENDING 审核同源：先认证、后展示）。
 * 普通用户始终不可上传舞伴照片（对舞伴唯一可写公开影响 = 认可 + 字典标签，约束不变）；
 * 本审核闸门仅面向舞伴本人（createdBy 匹配）与管理员。
 */
public enum DancerPhotoStatus {
    /** 待审核（舞伴本人上传后的初始状态，不对外展示） */
    PENDING,
    /** 公开（管理员审核通过，随舞伴主页公开展示） */
    PUBLIC,
    /** 已驳回（管理员审核未通过，不对外展示） */
    REJECTED
}
