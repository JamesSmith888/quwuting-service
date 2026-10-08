package org.quwuting.quwutingservice.venuepresence.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轨迹明细查询（{@code findTrackByUserIdSince}，2026-10-08 V46）的<b>真实数据库</b>契约验证。
 *
 * <h3>为什么需要它</h3>
 * 本查询是 admin「位置轨迹」卡的唯一取数源，三个执行期假设在纯逻辑单测里完全看不见：
 * <ul>
 *   <li><b>Pageable 上限</b>是否真的施加到 JPQL（截断语义的物理前提）；</li>
 *   <li><b>坐标列类型</b>（{@code double} ↔ {@code Double}）在驱动层返回什么——
 *       {@code SELECT Object[]} 的元素静态类型是 {@code Object}，编译器不会替我们报错
 *       （同 {@link VenueVisitDayQuerySqlTest} 的 {@code java.sql.Date} 事故复盘）；</li>
 *   <li>{@code createdAt} 元素能否直接转 {@link LocalDateTime}（新查询首次连库执行）。</li>
 * </ul>
 *
 * <p>运行方式：{@code ./mvnw test -Dtest=PresenceTrackQuerySqlTest -Drun.db.tests=true
 * -Dspring.profiles.active=mysql}（⚠️ 必须 mysql profile；dev profile 会连遗留 PG）。
 *
 * <p>⚠️ <b>运行时机 = 部署窗口项，勿随手跑</b>：加载全上下文会对所连库做应用启动级动作
 * ——Flyway 迁移检查与应用（生产库场景即把本版 V46 直接落库）+ {@code @Scheduled}
 * 调度器激活（{@code VenueVisitMetricsScheduler} 红线：首轮 60s 延迟，正常数秒内结束的
 * 测试不会触发，但超时/挂起可能产生一次生产刷新写）。⇒ 只与「迁移本来就要落库」的
 * 部署动作同时执行；默认（未设 {@code run.db.tests}）整类被 JUnit 条件禁用、不加载上下文。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class PresenceTrackQuerySqlTest {

    @Autowired
    private VenuePresencePingRepository pingRepository;

    /**
     * 对<b>真实库</b>执行轨迹查询：断言「能执行 + 上限生效 + 逐元素类型可安全消费」。
     * <p>
     * 样本用户取自既有命中证据（不造数据——测试数据会污染到访统计与物化表）；
     * 库内暂无命中证据时退化为空集，测试仍守「执行本身」（同 {@link VenueVisitDayQuerySqlTest}
     * 的断言强度说明）。
     */
    @Test
    void trackQueryExecutesWithLimitAndYieldsExpectedElementTypes() {
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        Long sampleUserId = pingRepository.findVisitorLastSeen(150, 150).stream()
                .map(row -> (Long) row[1])
                .findFirst()
                .orElse(-1L);

        List<Object[]> rows = pingRepository.findTrackByUserIdSince(
                sampleUserId, since, PageRequest.of(0, 50));

        assertTrue(rows.size() <= 50, "Pageable 上限未生效（实际行数 = " + rows.size() + "）");
        for (Object[] row : rows) {
            // ⛔ 逐元素校验执行期类型，禁强转遮蔽（同 VenueVisitDayQuerySqlTest 事故复盘）
            assertTrue(row[0] instanceof Long, "id 列类型：" + typeOf(row[0]));
            assertTrue(row[1] instanceof Long, "venueId 列类型：" + typeOf(row[1]));
            assertTrue(row[2] instanceof Integer, "distanceM 列类型：" + typeOf(row[2]));
            assertTrue(row[3] == null || row[3] instanceof Integer, "accuracyM 列类型：" + typeOf(row[3]));
            assertTrue(row[4] == null || row[4] instanceof Double,
                    "latitude 列返回类型不受支持：" + typeOf(row[4]));
            assertTrue(row[5] == null || row[5] instanceof Double,
                    "longitude 列返回类型不受支持：" + typeOf(row[5]));
            assertTrue(row[6] instanceof LocalDateTime,
                    "createdAt 返回类型不受支持：" + typeOf(row[6]));
        }
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
