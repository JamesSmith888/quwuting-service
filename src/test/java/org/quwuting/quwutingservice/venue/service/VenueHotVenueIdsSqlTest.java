package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VenueRepository#findHotVenueIds} 原生 SQL 的<b>真实数据库</b>验证（可选集成测试）。
 * <p>
 * 运行方式：配置好数据库与应用环境变量（与启动服务相同）后执行
 * {@code ./mvnw test -Drun.db.tests=true}。默认（未设该属性）整个测试类被 JUnit
 * 条件禁用，<b>不加载 Spring 上下文</b>——普通 {@code mvn test} / CI 零成本跳过。
 * <p>
 * 为什么需要它（2026-08-08 线上事故教训，见后端 AGENTS.md「native SQL 验证」）：
 * <ul>
 *   <li>Spring Data 的 {@code nativeQuery=true} 查询<b>只在执行期由数据库校验</b>——
 *       Hibernate 7 已移除 {@code hibernate.query.validate_native_queries}，
 *       {@code hibernate.query.startup_check} 只覆盖注册到 SessionFactory 的命名查询，
 *       Spring Data repository 的原生查询是首次调用时懒创建，启动期校验覆盖不到；</li>
 *   <li>Mockito 单元测试 mock 掉 repository，SQL 文本错误（列引用、别名作用域、
 *       时区/类型陷阱）<b>必然漏网</b>——本类即"改写 native SQL 后必须对真实库
 *       执行验证"的自动化载体（getHotVenueIds 首次调用会真实执行 SQL）。</li>
 * </ul>
 */
@SpringBootTest
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class VenueHotVenueIdsSqlTest {

    @Autowired
    private VenueLookupService venueLookupService;

    @Autowired
    private VenueRepository venueRepository;

    /**
     * 执行 findHotVenueIds 完整 SQL（经 VenueLookupService.getHotVenueIds 首次调用回源）。
     * 断言执行成功（非 null）——列引用/别名作用域错误会在数据库解析期直接抛异常。
     */
    @Test
    void hotVenueIdsQueryExecutesAgainstRealDatabase() {
        Set<Long> hotIds = venueLookupService.getHotVenueIds();
        assertNotNull(hotIds, "热门场所 ID 集合不应为 null（SQL 执行成功即证明列引用合法）");
        // 幂等/类型契约：结果应可遍历且为 ID 集合（查询返回 List<Long> → Set 包装）
        assertTrue(hotIds.stream().allMatch(id -> id != null && id > 0),
                "热门场所 ID 应为正数");
    }

    /**
     * 执行「热门」快捷筛选的列表查询（hotOnly=true，2026-08-08 新增，见
     * {@link VenueRepository#LIST_FILTERS} 的 {@code :hotOnly = false OR v.id IN :hotIds}
     * 谓词）。JPQL 语法层由 {@link VenueListQueryHqlSyntaxTest} 覆盖，本方法验证
     * <b>语义层</b>：boolean + Set&lt;Long&gt; 参数绑定、空集合 IN 语义（无热门场所时
     * 应返回空页而非报错）在真实数据库可执行。
     */
    @Test
    void hotFilteredListQueryExecutesAgainstRealDatabase() {
        Page<Venue> page = venueRepository.searchRankedNoLocation(
                null, null, null, null, null,
                org.quwuting.quwutingservice.venuereaction.ReactionCode.positiveCodeNames(),
                2 /* 积分权重（与 PointsProperties 默认一致；本测试只验证 SQL 语义层） */,
                true, venueLookupService.getHotVenueIds(), PageRequest.of(0, 20));
        assertNotNull(page, "热门筛选列表查询应执行成功（参数绑定/谓词合法）");
        // 热门筛选语义：返回的每一家都必须在热门集合内
        Set<Long> hotIds = venueLookupService.getHotVenueIds();
        assertTrue(page.getContent().stream().allMatch(v -> hotIds.contains(v.getId())),
                "hotOnly=true 时返回的场所必须全部属于热门集合");
    }
}
