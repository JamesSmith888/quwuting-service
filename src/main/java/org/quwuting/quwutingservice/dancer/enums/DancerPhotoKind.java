package org.quwuting.quwutingservice.dancer.enums;

/**
 * 舞伴相册媒体类型（2026-08-22 视频扩展，媒体无关契约——照片与视频同表同审核链）。
 * <ul>
 *   <li>{@link #PHOTO}：照片（默认，历史数据恒 PHOTO）；</li>
 *   <li>{@link #VIDEO}：短视频——管理员直发 + PENDING 逐条审核后公开，
 *       展示端以 coverUrl（封面帧图）为视觉占位，点击播放。</li>
 * </ul>
 * 扩展约定（09-dancer-and-points.md「媒体无关契约」）：遮挡机制（cost/unlocked/
 * blurUrl + 锁角标 + 解锁弹层）对视频复用同一契约；本期视频不上积分门槛，
 * 后续开放时 cost&gt;0 即走既有解锁通道。
 */
public enum DancerPhotoKind {
    /** 照片 */
    PHOTO,
    /** 短视频 */
    VIDEO
}
