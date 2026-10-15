package org.quwuting.quwutingservice.venuereaction.repository;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.support.HqlSyntaxAssertions;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VenueReactionRepository} JPQL 的 <b>HQL 语法级</b>校验（不依赖数据库，对齐
 * {@code VenueListQueryHqlSyntaxTest} 先例）。
 * <p>
 * 为什么需要它（2026-10-15 个人参与记忆）：{@code findMyReactionMarks} 在 JPQL 里用了
 * <b>聚合内的 CASE 分支</b>（{@code MAX(CASE WHEN ... THEN ... END)}），这是一条
 * 手写的、Mockito 单测 mock 掉 repository 后<b>必然漏网</b>的文本；Spring Data 对 {@code @Query}
 * 的 JPQL 又是首次执行时才解析——语法性回归会拖到线上首次调用才暴露。这里直接用 Hibernate 自带的
 * ANTLR 语法解析注解原文，普通 {@code mvn test} 即可拦下。
 * <p>
 * 局限：仅语法层。实体名 / 属性名 / 方言下的执行语义（{@code MAX(CASE...)} 在 MySQL 的结果）
 * 由默认禁用的 {@code VenueReactionMarksSqlTest} 在一次性数据库上验证。
 */
class VenueReactionQueryHqlSyntaxTest {

    @Test
    void findMyReactionMarksParsesAsHql() throws Exception {
        Method method = VenueReactionRepository.class.getMethod(
                "findMyReactionMarks", Long.class, java.util.List.class, java.time.LocalDate.class);
        Query query = method.getAnnotation(Query.class);
        assertNotNull(query, "findMyReactionMarks 必须是显式 @Query");
        assertFalse(query.nativeQuery(), "个人参与记忆走 JPQL（可被本语法测试覆盖）");

        HqlSyntaxAssertions.assertParses(query.value());
    }

    /**
     * 判别力自测：解析器必须真的会拒绝坏文本（缺 END 的 CASE）——否则上面两条是恒绿，
     * 「语法回归在 mvn test 里失败」只是一句空话。
     */
    @Test
    void harnessActuallyRejectsBrokenHql() {
        assertThrows(AssertionError.class, () -> HqlSyntaxAssertions.assertParses(
                "SELECT MAX(CASE WHEN r.reactionDate < :today THEN r.reactionDate FROM VenueReaction r"));
    }

    /**
     * 口径护栏：查询文本必须同时包含「今日」「今天之前」两个事实与软删过滤——
     * 少任何一个都会让前端三态静默退化（只剩今日 ⇒ 重置后痕迹消失；缺软删过滤 ⇒ 已取消的旧票
     * 变成「曾投」，与展示数字对不上）。
     */
    @Test
    void findMyReactionMarksKeepsBothFactsAndTheSoftDeleteFilter() throws Exception {
        Method method = VenueReactionRepository.class.getMethod(
                "findMyReactionMarks", Long.class, java.util.List.class, java.time.LocalDate.class);
        String hql = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        assertTrue(hql.contains("r.reactionDate = :today"), "今日这一票（reactedByMe 的写入侧语义）");
        assertTrue(hql.contains("r.reactionDate < :today"), "今天之前最近一次（lastReactedDate 的读取侧归属）");
        assertTrue(hql.contains("r.deleted = false"), "与计数同口径：软删行是已取消的票，不得变成曾投");
        assertTrue(hql.contains("GROUP BY r.venueId, r.reactionCode"), "粒度 = 场所 × 表情");
    }
}
