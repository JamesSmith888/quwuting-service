package org.quwuting.quwutingservice.user.enums;

/**
 * 管理端用户列表排序模式（2026-08-27 用户管理增强，GET /admin/users?sort=）。
 * <p>
 * 定位：运营按不同维度审视用户——默认按加入时间（新用户优先，运营关注新增）；
 * POINTS_DESC 识别高积分用户（活跃/异常）；LAST_ACTIVE_DESC 识别近期活跃用户
 * （运营拉新/召回参考，或找出流失用户）。
 * <p>
 * 实现约束：排序键必须<b>可被 SQL 表达</b>（分页排序在库内完成，禁内存排序——
 * 分页只取一页，内存排序会得出"页内正确、全局错误"的误导性结果）。LATEST_JOINED
 * 与 POINTS_DESC 走 JPQL；LAST_ACTIVE_DESC 的"最近活跃" = 跨行为表 MAX(created_at)
 * 聚合，走原生 SQL（见 UserRepository#findPageByFiltersOrderByLastActive）。
 */
public enum UserSortMode {
    /** 最新加入（默认：id 倒序） */
    LATEST_JOINED,
    /** 积分余额最多（qwt_points_accounts.balance 降序） */
    POINTS_DESC,
    /** 最近活跃（用户资料更新 / 积分流水 / 邀约 / 打卡 四源 MAX，降序） */
    LAST_ACTIVE_DESC
}
