package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.user.dto.response.TopVenue;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「常去门店」（{@link UserBehaviorRepository#listVenueAffinityByUserIds} +
 * {@link AdminUserStatsService#topVenuesFor}）原生 SQL 的<b>真实数据库</b>验证
 * （可选集成测试；2026-09-29）。
 * <p>
 * 运行方式：配置好数据库与应用环境变量（与启动服务相同）后执行
 * {@code ./mvnw test -Drun.db.tests=true}。默认（未设该属性）整个测试类被 JUnit
 * 条件禁用，<b>不加载 Spring 上下文</b>——普通 {@code mvn test} / CI 零成本跳过。
 * <p>
 * 为什么需要它（后端 AGENTS.md「native SQL 验证」，2026-08-08 线上事故教训）：
 * Spring Data 的 {@code nativeQuery=true} 查询<b>只在执行期由数据库校验</b>，
 * Mockito 单测 mock 掉 repository 后 SQL 文本错误（列引用、别名作用域）必然漏网。
 * 本类的两个断言分别盯住两类易错点：
 * <ol>
 *   <li><b>列引用合法性</b>——{@code EVENT_DETAIL_UNION} 的 {@code ref_id} 在 18 个
 *       分支里类型不一（门店 / 舞伴 / 招工 / 公告 id，另有 {@code CAST(NULL AS SIGNED)}），
 *       {@code GROUP BY} 与 {@code IN (:venueEventTypes)} 都要在真实库上跑通才作数；</li>
 *   <li><b>阈值与门店过滤的服务层契约</b>——下发的每一条 {@link TopVenue} 必须
 *       {@code count >= 2} 且门店存在（已软删的门店不当身份标签）。</li>
 * </ol>
 */
@SpringBootTest
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class AdminUserTopVenueSqlTest {

    private static final List<String> VENUE_EVENT_CODES = Arrays.stream(UserBehaviorEvent.values())
            .filter(event -> event.refKind() == UserBehaviorEvent.RefKind.VENUE)
            .map(UserBehaviorEvent::code)
            .toList();

    @Autowired
    private UserBehaviorRepository behaviorRepository;

    @Autowired
    private AdminUserStatsService statsService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void venueAffinityQueryExecutesAgainstRealDatabase() {
        List<Long> ids = firstUserIds();
        List<UserBehaviorRepository.VenueAffinityRow> rows = behaviorRepository
                .listVenueAffinityByUserIds(ids, LocalDate.now().minusDays(89), VENUE_EVENT_CODES);
        assertNotNull(rows, "SQL 执行成功即证明列引用/别名作用域合法");
        assertTrue(rows.stream().allMatch(r -> r.getUserId() != null
                        && r.getRefId() != null
                        && r.getCnt() != null && r.getCnt() > 0),
                "每一行都应有用户 id、门店 id 与正次数");
    }

    @Test
    void topVenuesForHonoursThresholdAndVenueExistence() {
        Map<Long, TopVenue> topVenues = statsService.topVenuesFor(firstUserIds());
        assertNotNull(topVenues, "聚合应执行成功（含门店名批量解析）");
        assertTrue(topVenues.values().stream().allMatch(v -> v.venueId() != null
                        && v.name() != null && !v.name().isBlank()
                        && v.count() >= 2),
                "下发的常去门店必须存在且达到最小次数阈值（单次动作不算身份标签）");
    }

    private List<Long> firstUserIds() {
        return userRepository.findAll(PageRequest.of(0, 10)).getContent()
                .stream().map(User::getId).toList();
    }
}
