package org.quwuting.quwutingservice.bulletin.repository;

import org.quwuting.quwutingservice.bulletin.entity.BulletinView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 快讯浏览统计仓储（2026-09-11，docs/agents/47-bulletins.md「九、浏览统计」；V20）。
 * <p>
 * <b>职责分工</b>：本仓储只承载<b>读</b>（列表页整页聚合计数）；<b>写</b>（批量
 * 展示上报）在 {@code BulletinViewService} 用 JdbcTemplate 单语句多行 upsert
 * 完成——JPA 仓储无法表达「N 行 VALUES + ON DUPLICATE KEY UPDATE」的动态语句，
 * 而逐条 upsert 会放大到 N 次 DB 往返（违反「最少 DB 往返」第一约束）。
 * <p>
 * <b>计数不缓存</b>：与表态域同理由（快讯体量小，缓存收益低于陈旧风险），
 * 每次请求按整页 id 一次 IN + GROUP BY 聚合。
 */
public interface BulletinViewRepository extends JpaRepository<BulletinView, Long> {

    /**
     * 整页快讯的累计查看人数（列表接口用）：一次 IN 查询覆盖多条内容。
     * 返回 {@code Object[]{bulletinId, count}}；count=0 的行不存在（GROUP BY），
     * 调用方判空补 0。命中 (bulletin_id, view_date) 索引前缀，毫秒级。
     */
    @Query(value = "SELECT v.bulletin_id, COUNT(*) AS cnt " +
                   "FROM qwt_bulletin_views v " +
                   "WHERE v.bulletin_id IN :bulletinIds " +
                   "GROUP BY v.bulletin_id",
           nativeQuery = true)
    List<Object[]> countByBulletinIds(@Param("bulletinIds") List<Long> bulletinIds);
}
