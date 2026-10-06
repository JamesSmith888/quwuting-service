package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.config.VenueHeatWeights;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到访项取数来源的静态门禁（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * <b>它防的是什么（本类存在的唯一理由）</b>：到访人数进排序时，最自然的"简化"是把公式里那截
 * 物化表子查询直接换成对原始 ping 表的距离过滤，例如
 * {@code (SELECT COUNT(DISTINCT p.userId) FROM VenuePresencePing p WHERE p.venueId = v.id AND p.distanceM <= 150)}。
 * 它语法成立、能跑、数字也"看起来对"，但会<b>静默丢掉两件正确性</b>：
 * <ol>
 *   <li><b>同址归因</b>——同楼竞品（坐标相距 ≤50m、定位分不开）会各自吃掉一份整楼人流，
 *       比同等人流的独栋店多一倍（52 号 §1.1 第 1 条明文禁止绕过归因）；</li>
 *   <li><b>排除内部账号</b>——到访是低基数信号（2026-10-06 现网 51 条 ping 中 ADMIN 一人占 51%），
 *       不排除等于平台自己人直接刷分。</li>
 * </ol>
 * ⇒ 取数唯一合规路径 = 读 {@code qwt_venue_visit_metrics}（归因 + 分摊 + 排除，由定时任务物化）。
 * <p>
 * <b>为什么必须是机器门禁而不是注释</b>：这条替换是"顺手优化"，改动者不会觉得自己在违规；
 * 而症状（某栋楼两家店一起往上浮、内部账号分数偏高）只在数据规模起来后才可见，
 * 且会被读成"数据就是这样"。本仓既有同族先例：{@code VenueListQueryHqlSyntaxTest} 的括号配平门禁
 * 拦的也是"改 SQL 时顺手吃掉一个右括号"这类不可见回归。
 * <p>
 * 局限：只做文本断言（取数来源 + 免计基数常量未被硬编码漂移），不验数值语义——
 * 数值正确性由 {@code VenueHeatServiceTest} 的到访项四条用例与真库只读复核承担。
 */
class VenueHeatVisitSourceTest {

    /** 物化表在两个镜像里的引用形态：JPQL 用实体名，native 用表名 */
    private static final String JPQL_SOURCE = "VenueVisitMetric";
    private static final String NATIVE_SOURCE = "qwt_venue_visit_metrics";

    /** 被禁止的取数来源（原始 ping 表）：两个镜像里的引用形态 */
    private static final String[] FORBIDDEN_SOURCES = {"VenuePresencePing", "qwt_venue_presence_pings"};

    @Test
    void heatBehaviorReadsMaterializedVisitTable() throws Exception {
        String hql = readStaticStringField("HEAT_BEHAVIOR");
        assertTrue(hql.contains(JPQL_SOURCE),
                "HEAT_BEHAVIOR 的到访项必须读物化表（" + JPQL_SOURCE + "）——"
                        + "直读原始 ping 表会绕过同址归因与内部账号排除，见类注释");
        assertNoForbiddenSource("HEAT_BEHAVIOR", hql);
    }

    @Test
    void hotVenueQueryReadsMaterializedVisitTable() throws Exception {
        String sql = declaredQueryText("findHotVenueIds");
        assertTrue(sql.contains(NATIVE_SOURCE),
                "findHotVenueIds 的到访项必须读物化表（" + NATIVE_SOURCE + "）——同 HEAT_BEHAVIOR 口径");
        assertNoForbiddenSource("findHotVenueIds", sql);
    }

    @Test
    void heatCountersReadMaterializedVisitTable() throws Exception {
        String sql = declaredQueryText("countHeatCounters");
        assertTrue(sql.contains(NATIVE_SOURCE),
                "countHeatCounters 的到访计数必须与排序同源读物化表（热度页与列表排位不得打架）");
        assertNoForbiddenSource("countHeatCounters", sql);
    }

    /**
     * 免计基数与权重必须由 {@link VenueHeatWeights} 常量<b>拼接</b>而来（而非两处各写一个数字）。
     * <p>
     * <b>⚠️ 断言前必须去掉全部空白</b>：{@code HEAT_BEHAVIOR} 是 Java 文本块拼出来的，而文本块
     * 会<b>剥掉每行的尾随空白</b> ⇒ 源码里的 {@code ) * """ + VISIT} 在最终文本里是 {@code ) *10}
     * （无空格）。按 {@code "* 10"} 断言会**恒红**——本用例第一版正是这么写错的，
     * 也正是这次"新断言先证明它会红"的例行变异测试把它暴露出来的（红的是断言，不是实现）。
     * ⇒ 判据：**对拼接产物做文本断言时，先归一空白**，否则你测的是排版不是语义。
     * <p>
     * <b>局限（诚实登记）</b>：本用例只能拦住"两处数字不一致"的漂移（改常量漏改镜像、
     * 或改镜像时裸写了另一个数字）——若有人把常量值原样硬编码进 SQL，文本上无法区分。
     * 那属于代码评审范畴，门禁不假装能覆盖。
     */
    @Test
    void visitTermUsesWeightConstants() throws Exception {
        for (String label : new String[]{"HEAT_BEHAVIOR", "findHotVenueIds"}) {
            String text = compact(label.equals("HEAT_BEHAVIOR")
                    ? readStaticStringField("HEAT_BEHAVIOR") : declaredQueryText("findHotVenueIds"));
            assertTrue(text.contains("*" + VenueHeatWeights.VISIT),
                    label + " 的到访项权重必须取 VenueHeatWeights.VISIT（当前 "
                            + VenueHeatWeights.VISIT + "），裸写数字会在改常量时静默漂移");
            assertTrue(text.contains("-" + VenueHeatWeights.VISIT_FREE_TIER),
                    label + " 的到访项免计基数必须取 VenueHeatWeights.VISIT_FREE_TIER（当前 "
                            + VenueHeatWeights.VISIT_FREE_TIER + "）");
            // 免计基数必须真的被"钳到非负"：GREATEST(0, …) 是把 1 人/2 人噪声整体压掉的那个动作
            assertTrue(text.contains("GREATEST(0,"),
                    label + " 的到访项必须用 GREATEST(0, n − 免计基数) 收敛非负——"
                            + "否则人数低于免计基数的门店会拿到负分（扣分），与「只做加性、永不折减」冲突");
        }
    }

    /** 去掉全部空白：Java 文本块会剥掉行尾空白，按"带空格的源码形态"断言会恒红（见上方注释） */
    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static void assertNoForbiddenSource(String label, String text) {
        for (String forbidden : FORBIDDEN_SOURCES) {
            assertFalse(text.contains(forbidden),
                    label + " 不得直接引用原始到访表（" + forbidden + "）——"
                            + "那是绕过同址归因/分摊/内部账号排除的写法，见类注释");
        }
    }

    private static String readStaticStringField(String name) throws Exception {
        Field field = VenueRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /** 反射取 {@code @Query} 的 value 文本（与 Spring 启动时传入 Hibernate 的最终 SQL 一致） */
    private static String declaredQueryText(String methodName) throws Exception {
        for (Method method : VenueRepository.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            org.springframework.data.jpa.repository.Query q =
                    method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
            if (q != null) {
                return q.value();
            }
        }
        throw new AssertionError("未找到 @Query 方法 " + methodName + "（疑似被改名，门禁需同步）");
    }
}
