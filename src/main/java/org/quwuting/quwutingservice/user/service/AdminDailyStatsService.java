package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.user.dto.response.AdminDailyStatItem;
import org.quwuting.quwutingservice.user.repository.UserDailyStatsRepository;
import org.quwuting.quwutingservice.user.repository.UserDailyStatsRepository.DailyStatsRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端运营大盘「按日统计」服务（2026-09-06，docs/agents/35-dashboard-stats.md；
 * 仅 ADMIN 消费）。
 * <p>
 * 定位：把「平台到底有多少真实用户」讲清楚——微信后台累计/访问口径含游客与
 * 审核流量，DB 注册数才是登录建号用户。本服务把三线趋势（注册 / 打开 / 真实
 * 互动）+ 打卡型噪音识别一次取回，供 admin-web Dashboard 渲染（30 天折线 +
 * 噪音占比），口径单一权威 = {@link UserDailyStatsRepository}。
 * <p>
 * 使用约束：<b>MySQL 8 方言查询</b>（生产 RDS MySQL，2026-08-30 起），
 * 勿在 PG 环境执行（本地联调需连 application-mysql.yaml 的 RDS）。
 */
@Service
@RequiredArgsConstructor
public class AdminDailyStatsService {

    /** 窗口钳制：最少 7 天（看周趋势）、最多 90 天 */
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final UserDailyStatsRepository userDailyStatsRepository;

    /**
     * 近 N 天（含今日）大盘按日序列。
     *
     * @param days 窗口天数（钳制 7~90；缺省 30）
     */
    @Transactional(readOnly = true)
    public List<AdminDailyStatItem> dailyStats(int days) {
        int window = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate since = LocalDate.now().minusDays(window - 1L);
        return userDailyStatsRepository.countDailyStats(since).stream()
                .map(this::toItem)
                .toList();
    }

    private AdminDailyStatItem toItem(DailyStatsRow r) {
        return new AdminDailyStatItem(
                r.getDay(),
                nz(r.getRegistered()),
                nz(r.getOpened()),
                nz(r.getInteractive()),
                nz(r.getNoisy()));
    }

    /** SQL COALESCE 已补零，此处兜底 null 防御（投影层极端情况） */
    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
