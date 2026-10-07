package org.quwuting.quwutingservice.spend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端账本统计<b>口径</b>一致性门禁（2026-10-07，零依赖：纯字符串断言 + 注解反射；
 * 不连库、不起 Spring 容器）。与 {@code UserStatsSqlMirrorTest} /
 * {@code UserBehaviorCatalogMirrorTest} 同族——那两道守「用户范围谓词」与「行为事件目录」，
 * 本类守「<b>使用事实 vs 账面金额</b>」这条新口径。
 *
 * <h2>为什么需要它（根因）</h2>
 * {@code SpendStatsRepository} 曾把 {@code e.deleted = 0} 内联抄进 7 条查询，
 * 于是<b>用户在小程序删除一条记账记录，admin 侧「计时 · 账本使用」的记账用户数 /
 * 场次 / 活跃 / 分类 / 门店排行集体下跌</b>。数据其实没丢（软删行完整保留在
 * {@code qwt_spend_entries}，全库无硬删路径），丢的是统计可见性。
 * <p>
 * 错误决策的本质不是"少写了一个条件"，而是<b>把两种不相容的语义塞进同一列</b>：
 * 用户撤回的是<b>数据</b>，撤不回<b>行为</b>；但金额口径下"删除"恰恰是在表达
 * "这笔不算"。原实现让计数也走了账面口径，于是用户的正常纠错动作被误读成
 * 使用行为的否定——且<b>完全静默</b>（SQL 正常执行、无异常、无告警）。
 *
 * <h2>判据（七类）</h2>
 * <ol>
 *   <li><b>两个常量必须显式表态 deleted</b>：事实口径含 {@code deleted IN (0,1)}、
 *       账面口径含 {@code deleted = 0}。<b>禁退化为恒真条件</b>（{@code 1=1}）——
 *       否则无法区分「有意包含软删」与「忘了写过滤」，本门禁也失去意义；</li>
 *   <li><b>口径常量必须自带首尾换行</b>（2026-10-07 事故新增）：Java 文本块会剥掉
 *       结束定界符前的换行，常量若无前导换行，消费方拼出的 SQL 会 token 粘连
 *       （{@code AND e.deleted = 0ORDER BY}）⇒ 接口 500、前端查不出数据，
 *       而编译与其余门禁<b>全绿</b>；</li>
 *   <li><b>拼接后无 token 粘连</b>（同上新增）：断言运行期真实值
 *       （{@code @Query.value()} 已是编译期拼好的最终 SQL），能看见「源码看着对、
 *       拼出来粘连」这类缺陷——<b>本事故唯一可被静态拦住的形态</b>；</li>
 *   <li><b>计数走事实口径</b>（{@link #FACT_METHODS}）：每条使用情况聚合必须引用
 *       {@link SpendStatsSql#FACT_ENTRY}。<b>唯一例外</b>是 {@code listUserEntries}——
 *       它是明细读取（「账上现在有什么」），不是「用过没有」；</li>
 *   <li><b>金额走账面口径</b>（{@link #LEDGER_METHODS}）。<b>唯一例外</b>是
 *       {@code countDaily}——纯计数趋势，不含金额聚合；</li>
 *   <li><b>无内联抄写</b>：任何 {@code @Query} 在扣除两枚常量与跨仓
 *       {@code USER_SCOPE} 之后，不得再出现 {@code e.deleted}。
 *       <b>为什么只查 {@code e.} 前缀</b>：{@code USER_SCOPE} 本身合法地含
 *       {@code u.deleted = false}（用户软删），若按裸 {@code deleted} 检测会把
 *       <b>合规引用误判成抄写</b>——门禁自身误报一次，团队就会开始整体忽略它；
 *       门禁必须<b>精确到不可能误报</b>，否则它的警告会被当成噪音；</li>
 *   <li><b>分组聚合不得用 CROSS JOIN</b>：分类/门店/用户列表三条查询是
 *       <b>分组</b>派生表——账面侧可能整组无行（该分类/门店条目全被软删），
 *       CROSS JOIN 会让这一组<b>整个消失</b>，即本次要修的现象换个维度复发；
 *       必须 {@code LEFT JOIN} + {@code COALESCE(...,0)}。</li>
 * </ol>
 *
 * <p><b>局限</b>：断言作用在 SQL 文本层，<b>不执行 SQL</b>（按项目验证深度红线，
 * 不连库）。但它拦住的正是最高危形态——口径回退、内联抄写、分组丢行、token 粘连；
 * 其中<b>token 粘连</b>是本项目至今唯一一起「编译 + 既有门禁全绿、却让接口 500」
 * 的缺陷类型。
 */
class SpendStatsScopeMirrorTest {

    /** admin 账本统计的全部查询方法（新增统计方法须纳入本清单） */
    private static final List<String> STAT_METHODS = List.of(
            "sumSummary", "countDaily", "sumByCategory",
            "sumByVenueTop", "listUsageUsers", "sumUserSummary", "listUserEntries");

    /**
     * 必须走<b>事实口径</b>的查询（= 一切"使用情况"聚合）。
     * <p><b>唯一例外是 {@code listUserEntries}</b>：它是<b>明细读取</b>
     * （"这个用户账上现在有哪些条目"），不是"用过没有"的聚合，故只走账面口径。
     * 这是<b>有意的语义区分</b>，不是遗漏——若把它改成事实口径，用户详情页会
     * 列出他自己已删除的条目。
     */
    private static final List<String> FACT_METHODS = List.of(
            "sumSummary", "countDaily", "sumByCategory",
            "sumByVenueTop", "listUsageUsers", "sumUserSummary");

    /**
     * 必须走<b>账面口径</b>的查询。
     * <p><b>唯一例外是 {@code countDaily}</b>：它是纯计数趋势（三条都是条目数 /
     * 活跃用户数），不含任何金额聚合，因此不需要账面口径。
     */
    private static final List<String> LEDGER_METHODS = List.of(
            "sumSummary", "sumByCategory", "sumByVenueTop",
            "listUsageUsers", "sumUserSummary", "listUserEntries");

    /** 分组聚合的查询（账面侧可能整组无行 ⇒ 必须 LEFT JOIN） */
    private static final List<String> GROUPED_METHODS = List.of(
            "sumByCategory", "sumByVenueTop", "listUsageUsers");

    /** 账目表别名——内联检测只针对它，{@code u.deleted}（用户软删）属合法引用 */
    private static final String ENTRY_ALIAS = "e.deleted";

    /** 跨仓常量：用户范围口径本身含 {@code u.deleted = false}，内联检测须将其排除 */
    private static final String USER_SCOPE = org.quwuting.quwutingservice.user.repository.UserStatsSql.USER_SCOPE;

    @Test
    void scopeConstantsDeclareDeletedExplicitly() {
        assertTrue(SpendStatsSql.FACT_ENTRY.contains("deleted IN (0, 1)"),
                "事实口径必须显式包含软删（deleted IN (0,1)）——否则用户删一条账目，"
                        + "admin 使用盘子会随之缩水；用户撤回的是数据，撤不回行为");
        assertTrue(SpendStatsSql.LEDGER_ENTRY.contains("deleted = 0"),
                "账面口径必须只算未删行（deleted = 0）——用户删除即表达「这笔不算」，"
                        + "计入消费总额会让运营误判真实消费水平");
        assertFalse(SpendStatsSql.FACT_ENTRY.equals(SpendStatsSql.LEDGER_ENTRY),
                "两个口径常量相同——双口径退化为单口径，等于本次修复没有生效");
    }

    /**
     * <b>token 粘连防护（2026-10-07 事故，最高危判据）</b>。
     * <p>
     * 事故形态：单用户汇总与流水明细把常量拼成
     * {@code "AND"} + {@code " "} + {@code FACT_ENTRY} + {@code "\n ORDER BY"}，
     * 但<b>Java 文本块会剥掉结束定界符前的那个换行</b>，于是运行期实际 SQL 是
     * {@code AND e.deleted = 0ORDER BY ...} —— <b>token 粘连、SQL 语法错误、接口 500</b>，
     * 前端表现为「查不出数据」。而编译、tsc、其余 4 项门禁<b>全绿</b>：
     * 没有任何一处校验<b>拼接后</b>的 SQL。
     * <p>
     * <b>为什么必须断言"常量自带换行"</b>：本仓所有统计 SQL 都以
     * {@code "... AND" + SpendStatsSql.X + "\n   ..."} 形式拼装。若常量不带首尾换行，
     * 每一条消费方都必须自己记得补换行——这等于把契约散落到 N 个调用点，
     * 正是本次事故的成因。故把换行<b>放进常量本身</b>，并在本门禁锁死。
     */
    @Test
    void scopeConstantsCarrySurroundingNewlines() {
        assertTrue(SpendStatsSql.FACT_ENTRY.startsWith("\n"),
                "事实口径常量必须以换行开头——Java 文本块会剥掉结束定界符前的换行，"
                        + "常量不带前导换行会让消费方拼出 `AND e.deleted = 0ORDER BY` 这类"
                        + "token 粘连（2026-10-07 事故：接口 500、前端查不出数据，"
                        + "而编译与门禁全绿）");
        assertTrue(SpendStatsSql.FACT_ENTRY.endsWith("\n"),
                "事实口径常量必须以换行结尾——否则后续 token 会粘在谓词后面");
        assertTrue(SpendStatsSql.LEDGER_ENTRY.startsWith("\n"),
                "账面口径常量必须以换行开头（同上：token 粘连事故）");
        assertTrue(SpendStatsSql.LEDGER_ENTRY.endsWith("\n"),
                "账面口径常量必须以换行结尾（同上）");
    }

    /**
     * 拼接后<b>不得出现 token 粘连</b>——守"常量引用正确"这一层的最终形态。
     * <p>
     * 断言的是 {@link #sqlOf} 拿到的<b>运行期真实值</b>（Java 注解常量在编译期
     * 已完成字符串拼接，{@code @Query.value()} 就是最终 SQL），因此它能看见
     * 「源码看着对、拼出来粘连」这类缺陷——<b>这正是本次事故唯一能被静态拦住的形态</b>。
     */
    @Test
    void noTokenGlueInAssembledSql() {
        // 粘连形态：token 末尾紧跟 SQL 关键字或闭合括号
        String[][] glues = {
                {"deleted = 0ORDER", "账面口径紧跟 ORDER"},
                {"deleted = 0LIMIT", "账面口径紧跟 LIMIT"},
                {"deleted = 0WHERE", "账面口径紧跟 WHERE"},
                {"deleted = 0GROUP", "账面口径紧跟 GROUP"},
                {"deleted = 0UNION", "账面口径紧跟 UNION"},
                {"deleted = 0ON", "账面口径紧跟 ON"},
                {"deleted = 0JOIN", "账面口径紧跟 JOIN"},
                {"deleted = 0LEFT", "账面口径紧跟 LEFT"},
                {"deleted = 0)", "账面口径紧跟 )"},
                {"(0, 1))", "事实口径紧跟 )"},
                {"(0, 1)ORDER", "事实口径紧跟 ORDER"},
                {"(0, 1)WHERE", "事实口径紧跟 WHERE"},
                {"(0, 1)GROUP", "事实口径紧跟 GROUP"},
        };
        for (Method m : statMethods()) {
            String sql = sqlOf(m);
            for (String[] g : glues) {
                assertFalse(sql.contains(g[0]),
                        m.getName() + " 拼接后出现 token 粘连（'" + g[0] + "'：" + g[1] + "）"
                                + "——运行期会 SQL 语法错误、接口 500。根因：Java 文本块会剥掉"
                                + "结束定界符前的换行，消费方拼接处必须补换行，"
                                + "或让口径常量自带首尾换行（见 scopeConstantsCarrySurroundingNewlines）");
            }
        }
    }

    @Test
    void countAggregationsUseFactScope() {
        for (String name : FACT_METHODS) {
            assertTrue(sqlOf(methodByName(name)).contains(SpendStatsSql.FACT_ENTRY),
                    name + " 是使用情况聚合却未引用事实口径（" + SpendStatsSql.FACT_ENTRY + "）"
                            + "——计数类聚合必须走事实口径，否则用户删除账目会抹掉使用记录");
        }
    }

    @Test
    void moneyAggregationsUseLedgerScope() {
        for (String name : LEDGER_METHODS) {
            assertTrue(sqlOf(methodByName(name)).contains(SpendStatsSql.LEDGER_ENTRY),
                    name + " 未引用账面口径（" + SpendStatsSql.LEDGER_ENTRY + "）"
                            + "——金额类聚合必须走账面口径，撤回的金额不应计入消费总额");
        }
    }

    @Test
    void noInlineDeletedPredicate() {
        for (Method m : statMethods()) {
            String sql = sqlOf(m);
            // 先摘掉两枚口径常量与跨仓 USER_SCOPE（后者合法含 u.deleted=false），
            // 剩余部分若还出现 e.deleted，即为内联抄写
            String stripped = sql
                    .replace(SpendStatsSql.FACT_ENTRY, "")
                    .replace(SpendStatsSql.LEDGER_ENTRY, "")
                    .replace(USER_SCOPE, "");
            assertFalse(stripped.contains(ENTRY_ALIAS),
                    m.getName() + " 在常量之外仍内联 " + ENTRY_ALIAS + " —— 口径会再次散落成"
                            + "多份抄写（USER_SCOPE 曾被抄 5 份的先例），必须引用 SpendStatsSql 常量");
        }
    }

    @Test
    void groupedQueriesUseLeftJoinNotCrossJoin() {
        for (String name : GROUPED_METHODS) {
            String sql = sqlOf(methodByName(name));
            assertFalse(sql.contains("CROSS JOIN"),
                    name + " 的账面派生表是分组聚合，条目全部被软删时该组在账面侧无行，"
                            + "CROSS JOIN 会让这一组整个消失（即本次要修的现象在分类/门店维度复发）"
                            + "——必须 LEFT JOIN 并对金额 COALESCE 补零");
            assertTrue(sql.contains("LEFT JOIN"),
                    name + " 缺少 LEFT JOIN");
            assertTrue(sql.contains("COALESCE(m."),
                    name + " 的账面金额未 COALESCE 补零——LEFT JOIN 未命中时会返回 null，"
                            + "前端将渲染出空金额");
        }
    }

    // ── 反射工具（同仓门禁惯例：零 Spring 容器） ─────────────────────────────

    private static List<Method> statMethods() {
        return java.util.Arrays.stream(SpendStatsRepository.class.getDeclaredMethods())
                .filter(m -> STAT_METHODS.contains(m.getName()))
                .toList();
    }

    private static Method methodByName(String name) {
        Method found = java.util.Arrays.stream(SpendStatsRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SpendStatsRepository 找不到方法 " + name
                                + "——新增统计方法须同步登记进本门禁的 STAT_METHODS 清单，"
                                + "否则它将不受口径校验保护"));
        return found;
    }

    /** 取 @Query 字面量；缺失即失败（防止有人删掉注解让门禁静默跳过） */
    private static String sqlOf(Method m) {
        Query q = m.getAnnotation(Query.class);
        if (q == null || q.value() == null || q.value().isEmpty()) {
            throw new AssertionError(m.getName() + " 缺少 @Query 注解或 SQL 为空——"
                    + "本门禁依赖注解反射，请确认查询未被改成派生查询或注解被移除");
        }
        return q.value();
    }
}