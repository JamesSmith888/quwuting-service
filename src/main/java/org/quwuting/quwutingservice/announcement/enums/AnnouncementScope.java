package org.quwuting.quwutingservice.announcement.enums;

/**
 * 公告可见范围（2026-09-01）：一期仅 ALL（全体用户），预留 CITY（城市粒度）。
 * <p>
 * 数据列 varchar 存储，后端查询当前不按 scope 过滤（ALL 全覆盖）；CITY 为
 * 未来扩展预留，字段语义 = 公告面向的用户集合，不影响存量数据结构。
 */
public enum AnnouncementScope {
    ALL,
    CITY
}
