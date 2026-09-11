package org.quwuting.quwutingservice.bulletin.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.bulletin.repository.BulletinViewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快讯浏览统计服务（2026-09-11，docs/agents/47-bulletins.md「九、浏览统计」）。
 * <p>
 * <b>口径：信息流展示即计</b>——快讯在信息流被加载展示时，前端批量 fire-and-forget
 * 上报本次渲染出的条目 id；服务端按 {@code (bulletin_id, user_id, view_date)} 去重
 * （唯一键承载"每人每条每天一次"语义，写入走批量 upsert，一次 DB 往返覆盖整页）。
 * <p>
 * <b>为什么用 JdbcTemplate 而不是 JPA 仓储</b>：上报是"整页 id 集合"的批量写，
 * JPA 仓储表达不了「N 行 VALUES + ON DUPLICATE KEY UPDATE」的动态语句；逐条
 * upsert 会放大到每页 10 条 × 跨洲往返（违反「最少 DB 往返」第一约束，见
 * 29-performance.md）。JdbcTemplate 单语句多行 VALUES 恒为 1 次往返。
 * <p>
 * 与门店域 {@code VenueViewService} 的差异：
 * <ul>
 *   <li><b>无匿名路径</b>：快讯接口全部需登录，上报端点同样鉴权，userId 恒非空，
 *       不需要门店那套匿名 IP 频控（Caffeine limiter）；</li>
 *   <li><b>无 source 维度</b>：快讯只有信息流一个展示入口，不按来源归因分列；</li>
 *   <li><b>无热度缓存失效</b>：快讯域没有热度统计，浏览数不需要联动逐出任何缓存。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BulletinViewService {

    private final JdbcTemplate jdbcTemplate;
    private final BulletinViewRepository bulletinViewRepository;

    /**
     * 批量记录一次展示浏览（信息流每加载一页调用一次；fire-and-forget 语义由接口层/前端
     * 承担）。按 (bulletin_id, user_id, view_date) 去重：同日重复上报 ON DUPLICATE KEY
     * DO NOTHING，不影响计数。恒 1 次 DB 往返。
     *
     * @param bulletinIds 本页展示的快讯 id 集合（去重后写入；null/空 = no-op）
     * @param userId      当前用户（接口层已鉴权，恒非空）
     */
    @Transactional
    public void recordViews(List<Long> bulletinIds, Long userId) {
        if (bulletinIds == null || bulletinIds.isEmpty() || userId == null) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(bulletinIds);
        ids.removeIf(id -> id == null || id <= 0);
        if (ids.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "(?, ?, ?, ?, ?, ?)"));
        Object[] args = new Object[ids.size() * 6];
        int i = 0;
        for (Long id : ids) {
            args[i++] = id;
            args[i++] = userId;
            args[i++] = today;
            args[i++] = now;
            args[i++] = now;
            args[i++] = false;
        }
        jdbcTemplate.update(
                "INSERT INTO qwt_bulletin_views " +
                "(bulletin_id, user_id, view_date, created_at, updated_at, deleted) " +
                "VALUES " + placeholders + " ON DUPLICATE KEY UPDATE id = id",
                args);
    }

    /**
     * 单条快讯的累计查看人数（详情接口用；无记录 = 0）。
     * 快讯刚发布尚无展示时返回 0——前端 formatViewCount(0) → "0"（对齐门店卡片
     * "新店恒显示 0"的视觉统一）。
     */
    @Transactional(readOnly = true)
    public long countByBulletinId(Long bulletinId) {
        return countByBulletinIds(List.of(bulletinId)).getOrDefault(bulletinId, 0L);
    }

    /**
     * 整页快讯的累计查看人数（列表接口用）：一次 IN + GROUP BY 聚合，无 N+1。
     * 返回 map 恒含入参里的每个 id（无记录 = 0），调用方无需判空。
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countByBulletinIds(List<Long> bulletinIds) {
        if (bulletinIds == null || bulletinIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : bulletinViewRepository.countByBulletinIds(bulletinIds)) {
            Long bulletinId = (Long) row[0];
            long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            result.put(bulletinId, count);
        }
        for (Long id : bulletinIds) {
            result.putIfAbsent(id, 0L);
        }
        return result;
    }
}
