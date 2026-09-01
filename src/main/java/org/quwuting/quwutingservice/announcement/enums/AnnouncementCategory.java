package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告分类（2026-09-01）：NOTICE 运营公告 / DATA_UPDATE 数据更新公告。
 * <p>
 * DATA_UPDATE 是「数据更新公告」专属分类（自动或手动发布），列表/详情按
 * 分类渲染标签；同日防重唯一键仅作用于 SYSTEM + DATA_UPDATE 组合
 * （见 V7 迁移生成列）。
 */
public enum AnnouncementCategory {
    NOTICE,
    DATA_UPDATE
}
