package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.message.entity.Message;
import org.quwuting.quwutingservice.message.repository.MessageRepository;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReportLike;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportLikeRepository;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生查询方法名的<b>无库校验</b>（2026-10-07）。
 * <p>
 * Spring Data 的 {@code findByXxxAndYyy} 在<b>应用启动期</b>才解析方法名并校验属性——方法名里一个拼写错误
 * （属性不存在、关键字写错）不会让编译失败、也不会让任何不加载 Spring 上下文的单测失败，而是让整个应用
 * <b>启动失败</b>。本次改动新增 / 改名了若干派生查询（含一个很长的通知合并查询名），而本仓加载上下文的测试
 * 要么需要连生产库（{@code run.db.tests}，且会触发 Flyway），要么默认 profile 下无数据源根本起不来。
 * <p>
 * 这里用 Spring Data 公开的 {@link PartTree} 对每个未带 {@code @Query} 的派生方法名做<b>同一套解析</b>，
 * 实体属性解析失败会抛 {@code PropertyReferenceException}——把「启动期才炸」前移成「单测期就红」，且不需要任何数据库。
 * 它不验证 SQL 语义（那是 {@code CrowdReportQuerySqlTest} 的事），只验证「方法名能被 Spring Data 读懂」。
 */
class CrowdRepositoryDerivedQueryTest {

    private static final List<String> DERIVED_PREFIXES = List.of("find", "count", "exists", "delete", "get", "read", "query");

    private static List<String> derivedMethodNames(Class<?> repository) {
        List<String> names = new ArrayList<>();
        for (Method m : repository.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Query.class) || m.isDefault() || m.isSynthetic()) {
                continue;
            }
            boolean derived = DERIVED_PREFIXES.stream().anyMatch(p -> m.getName().startsWith(p));
            if (derived) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static void assertAllParse(Class<?> repository, Class<?> entity) {
        List<String> names = derivedMethodNames(repository);
        assertFalse(names.isEmpty(), repository.getSimpleName() + " 没有可校验的派生方法——反射筛选失效？");
        for (String name : names) {
            // 解析失败（属性不存在 / 关键字错）抛 PropertyReferenceException：此处让它直接冒泡，报错里带方法名
            try {
                PartTree tree = new PartTree(name, entity);
                assertTrue(tree.getParts().iterator().hasNext() || tree.isDelete() || tree.isCountProjection(),
                        name + " 解析后没有任何条件");
            } catch (RuntimeException e) {
                throw new AssertionError(repository.getSimpleName() + "#" + name + " 无法被 Spring Data 解析：" + e.getMessage(), e);
            }
        }
    }

    @Test
    void crowdReportRepositoryDerivedNamesParse() {
        assertAllParse(VenueCrowdReportRepository.class, VenueCrowdReport.class);
    }

    @Test
    void crowdReportLikeRepositoryDerivedNamesParse() {
        assertAllParse(VenueCrowdReportLikeRepository.class, VenueCrowdReportLike.class);
    }

    @Test
    void messageRepositoryDerivedNamesParse() {
        // 含 2026-10-07 新增的通知合并查询（名字很长，最容易拼错一个属性）
        assertAllParse(MessageRepository.class, Message.class);
    }
}
