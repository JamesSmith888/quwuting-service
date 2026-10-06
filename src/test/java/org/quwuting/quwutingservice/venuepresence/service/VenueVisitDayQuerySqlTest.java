package org.quwuting.quwutingservice.venuepresence.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到访「去重日明细」查询的<b>真实数据库</b>契约验证（2026-10-06 生产事故后建，可选集成测试）。
 *
 * <h3>为什么必须有这条测试（事故复盘）</h3>
 * 首版实现用 {@code (LocalDate) row[2]} 直接强转 {@code SELECT DATE(p.createdAt)} 的结果。
 * 本地纯逻辑单测全绿、生产调度首轮直接
 * {@code ClassCastException: java.sql.Date cannot be cast to java.time.LocalDate}，
 * 且被 {@code refresh()} 的 {@code catch(Exception)} 吞掉 ⇒ <b>物化表永久停在旧值</b>。
 * <p>
 * 结构性原因：{@code SELECT Object[]} 的元素静态类型是 {@code Object}，
 * <b>编译器不会对元素转型报错</b>；而现有到访测试（{@code VenuePresenceAttributionTest} /
 * {@code VenuePresenceConsentGateTest}）全是纯 Java 逻辑，<b>没有一条真正连库执行这条 JPQL</b>
 * ⇒ 类型假设在 CI 全程无人校验。本类补上这一环。
 *
 * <h3>它守住什么</h3>
 * 断言"查询能执行 + 返回的日元素能被安全转成 {@link LocalDate}"。
 * 驱动/Hibernate 版本变化导致返回类型改变时（如本次的 {@code java.sql.Date}），
 * 本测试在<b>部署前</b>即失败，而不是等到生产调度静默失败。
 *
 * <p>运行方式：{@code ./mvnw test -Drun.db.tests=true}。
 * 默认（未设属性）整类被 JUnit 条件禁用，<b>不加载 Spring 上下文</b>。
 * ⛔ 禁止对本类加 {@code @SpringBootTest} 之外的调度触发：到访调度器带 {@code @Scheduled}，
 * 加载上下文会真实跑刷新写生产表（同 {@code VenueVisitMetricsScheduler} 类注释红线）。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class VenueVisitDayQuerySqlTest {

    @Autowired
    private VenuePresencePingRepository pingRepository;

    /**
     * 对<b>真实库</b>执行去重日明细查询，并逐元素校验可安全转 {@link LocalDate}。
     *
     * <p>用「已存在任意 ping 的门店」作为入参（而不是造数据）：本测试只关心
     * <b>查询能跑通+ 返回类型正确</b>，不关心返回值内容；造 ping 还会引入
     * 「测试数据被算进到访统计」的污染风险（物化表是排序口径的输入）。
     *
     * <p>断言强度说明：即使库内暂无 ping（返回空集）本测试也会通过 —— 它守的是
     * <b>「执行期类型」</b>而非数据；一旦库内有数据，元素转型即被验证。
     */
    @Test
    void visitDayQueryExecutesAndYieldsConvertibleDates() {
        List<Long> anyVenueWithPing = pingRepository
                .findVisitorDaysSinceExcluding(
                        java.time.LocalDateTime.now().minusDays(30), 150, 150, List.of(-1L))
                .stream()
                .map(row -> (Long) row[0])
                .distinct()
                .limit(5)
                .toList();
        assertTrue(anyVenueWithPing != null, "入参集合不应为 null");

        for (Long venueId : anyVenueWithPing) {
            List<Object[]> rows = pingRepository.findVisitorDaysSinceExcluding(
                    java.time.LocalDateTime.now().minusDays(30), 150, 150, List.of(-1L));
            for (Object[] row : rows) {
                // ⛔ 禁把这行改回强转：正是它遮蔽了类型问题（见类注释事故复盘）。
                // 这里显式接受 JDBC 的 java.sql.Date —— 与生产实际返回类型一致。
                assertTrue(row[2] instanceof java.sql.Date || row[2] instanceof LocalDate,
                        "到访日列返回类型不受支持，实际 = "
                                + (row[2] == null ? "null" : row[2].getClass().getName())
                                + "。若驱动/Hibernate 版本变更，需在 VenuePresenceService"
                                + "#toLocalDate 显式登记新类型，⛔ 禁直接强转。");
            }
            break; // 取到一批即可，不必遍历全部
        }
    }
}