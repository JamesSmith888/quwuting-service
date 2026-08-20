package org.quwuting.quwutingservice.venue.enums;

/**
 * 门店相册照片状态（照片级"先审后发"审核闸门，与 DancerPhotoStatus 同构）。
 * <ul>
 *   <li>{@link #PENDING}：待审核——普通用户（UGC）上传后的初始状态，不对外展示，
 *       仅上传者本人与平台管理员可见；</li>
 *   <li>{@link #PUBLIC}：公开——管理员审核通过，或门店管理方（认领人/管理员）上传
 *       即直发（管理方可信写者，保留 venue 既有"直写公开"语义，不给 ADMIN 增加
 *       审核负担），随门店详情/列表轮播公开展示；</li>
 *   <li>{@link #REJECTED}：已驳回——管理员审核未通过（照片不实/违规等），不对外
 *       展示，上传者本人在编辑页可见状态并可删除重传。</li>
 * </ul>
 * 设计根因（AGENTS.md「门店照片域」）：门店照片是环境证据，UGC 价值高但同样
 * 存在低俗/广告/垃圾图风险——普通用户上传必须先审后发；管理方（canManage）
 * 对门店有管理责任，其上传直发公开（与旧 JSON 列直写语义一致）。
 */
public enum VenuePhotoStatus {
    /** 待审核（普通用户上传后的初始状态，不对外展示） */
    PENDING,
    /** 公开（管理方直发或管理员审核通过，随门店轮播公开展示） */
    PUBLIC,
    /** 已驳回（管理员审核未通过，不对外展示） */
    REJECTED
}
