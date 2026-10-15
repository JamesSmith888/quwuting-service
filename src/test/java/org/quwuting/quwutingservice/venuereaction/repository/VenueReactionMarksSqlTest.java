package org.quwuting.quwutingservice.venuereaction.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link VenueReactionRepository#findMyReactionMarks} 的<b>真实数据库</b>语义验证
 * （可选集成测试，对齐 {@code TimerShareLikeHistorySqlTest} / {@code WxSubscribeSqlTest} 先例；2026-10-15）。
 * <p>
 * 运行方式：{@code ./mvnw test -Drun.db.tests=true -Dspring.profiles.active=<一个可丢弃的库>}。
 * 默认（未设该属性）整个测试类被 JUnit 条件禁用，<b>不加载 Spring 上下文</b>。
 * ⚠️ 本仓 {@code application-mysql.yaml} 指向生产 RDS 且 Flyway 开——<b>不要</b>拿它跑；用本机一次性 MySQL 8。
 * <p>
 * 为什么必须有真库版本：语法测试（{@code VenueReactionQueryHqlSyntaxTest}）只证明 HQL 能解析；
 * 「聚合里的 CASE 在 MySQL 上到底返回什么」「软删行是否被过滤」「跨用户 / 跨场所是否串」是执行语义，
 * Mockito 单测 mock 掉 repository 后全部漏网。幻影用户 / 场所 id，类级 {@code @Transactional} 回滚。
 */
@SpringBootTest
@Transactional
@Tag("db")
@EnabledIfSystemProperty(named = "run.db.tests", matches = "true")
class VenueReactionMarksSqlTest {

    private static final long USER = 999_999_903L;
    private static final long OTHER_USER = 999_999_904L;
    private static final long VENUE = 999_999_911L;
    private static final long OTHER_VENUE = 999_999_912L;
    /** 固定「今天」（作为参数传入，不依赖真实时钟） */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Autowired
    private VenueReactionRepository repository;

    private void insert(long userId, long venueId, String code, LocalDate date, boolean deleted) {
        VenueReaction row = new VenueReaction();
        row.setUserId(userId);
        row.setVenueId(venueId);
        row.setReactionCode(code);
        row.setReactionDate(date);
        row.setDeleted(deleted);
        repository.saveAndFlush(row);
    }

    @Test
    void marksSeparateTodayFromTheLatestEarlierDay() {
        // HOT：更早两天 + 今天——今日为真，曾投取「今天之前最近一次」（10-14，而不是更早的 10-10）
        insert(USER, VENUE, "HOT", LocalDate.of(2026, 10, 10), false);
        insert(USER, VENUE, "HOT", LocalDate.of(2026, 10, 14), false);
        insert(USER, VENUE, "HOT", TODAY, false);
        // QUIET：只有更早的票——过零点后的典型状态（今日未投、有痕迹）
        insert(USER, VENUE, "QUIET", LocalDate.of(2026, 10, 13), false);
        // RECOMMEND：只有今天一票——没有「今天之前」的痕迹
        insert(USER, VENUE, "RECOMMEND", TODAY, false);
        // BAD_ENV：只有一条软删行（迁移前模型里已取消的票）——不得出现，与展示数字同口径
        insert(USER, VENUE, "BAD_ENV", LocalDate.of(2026, 10, 12), true);
        // 他人 / 他店的行：不得串入
        insert(OTHER_USER, VENUE, "MISMATCH", LocalDate.of(2026, 10, 12), false);
        insert(USER, OTHER_VENUE, "SMOKING", LocalDate.of(2026, 10, 12), false);

        List<Object[]> rows = repository.findMyReactionMarks(USER, List.of(VENUE), TODAY);

        Map<String, Object[]> byCode = new HashMap<>();
        for (Object[] row : rows) {
            assertEquals(VENUE, ((Number) row[0]).longValue(), "只返回被查询的场所");
            byCode.put((String) row[1], row);
        }
        assertEquals(3, byCode.size(), "HOT / QUIET / RECOMMEND；软删、他人、他店均不返回: " + byCode.keySet());

        assertEquals(1, ((Number) byCode.get("HOT")[2]).intValue());
        assertEquals(LocalDate.of(2026, 10, 14), byCode.get("HOT")[3]);

        assertEquals(0, ((Number) byCode.get("QUIET")[2]).intValue());
        assertEquals(LocalDate.of(2026, 10, 13), byCode.get("QUIET")[3]);

        assertEquals(1, ((Number) byCode.get("RECOMMEND")[2]).intValue());
        assertNull(byCode.get("RECOMMEND")[3]);

        assertFalse(byCode.containsKey("BAD_ENV"));
    }

    @Test
    void multiVenueQueryKeepsVenuesApart() {
        insert(USER, VENUE, "HOT", LocalDate.of(2026, 10, 14), false);
        insert(USER, OTHER_VENUE, "HOT", LocalDate.of(2026, 10, 11), false);

        Map<Long, LocalDate> lastByVenue = new HashMap<>();
        for (Object[] row : repository.findMyReactionMarks(USER, List.of(VENUE, OTHER_VENUE), TODAY)) {
            lastByVenue.put(((Number) row[0]).longValue(), (LocalDate) row[3]);
        }

        assertEquals(LocalDate.of(2026, 10, 14), lastByVenue.get(VENUE));
        assertEquals(LocalDate.of(2026, 10, 11), lastByVenue.get(OTHER_VENUE));
    }
}
