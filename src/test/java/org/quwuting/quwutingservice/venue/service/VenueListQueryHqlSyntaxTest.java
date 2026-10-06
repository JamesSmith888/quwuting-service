package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.support.HqlSyntaxAssertions;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VenueRepository} 列表 @Query 的 <b>HQL 语法级</b>校验（不依赖数据库）。
 * <p>
 * 为什么需要它（2026-08-08 确立，补「native SQL 验证」之外的 JPQL 校验空白）：
 * Spring Data repository 的 {@code @Query} JPQL 字符串<b>懒校验</b>——首次执行时
 * Hibernate 才解析，启动期校验覆盖不到（与 nativeQuery 同病，见
 * {@link VenueHotVenueIdsSqlTest} 注释）。本测试直接驱动 Hibernate 自带的 ANTLR
 * HQL 语法（{@code org.hibernate.grammars.hql}，grammar 类随 hibernate-core 发布），
 * 对全部列表查询的完整拼接文本做语法解析——共享的 {@link VenueRepository#LIST_FILTERS}
 * 片段（含 2026-08-08 新增的 {@code :hotOnly = false OR v.id IN :hotIds} 热门筛选谓词）
 * 一旦出现语法性回归（括号/操作符/字面量），普通 {@code mvn test} 立即失败，
 * 不再等真实数据库执行期才暴露。
 * <p>
 * 局限：仅语法层（grammar 解析成功即证明拼接文本结构合法）；语义层（实体名/属性名/
 * 参数绑定类型）仍需真实数据库验证——由 {@link VenueHotVenueIdsSqlTest} 模式的
 * DB 门禁测试覆盖（本类不加载 Spring 上下文，零成本跳过普通测试之外的负担）。
 */
class VenueListQueryHqlSyntaxTest {

    /** 与 VenueRepository @Query 拼接方式一致的完整 SELECT 语句（列表变体 + 检索专用方法）。
     *  2026-09-02 搜索增强：RECOMMENDED 系 ORDER BY 前插 RELEVANCE_KEYS（搜索相关度键，
     *  含全限定枚举 OPEN 比较与 ESCAPE 字面量）；LIST_FILTERS 现内嵌 KW_MATCH
     *  （EXISTS 子查询 + ESCAPE 字面量）与 :filterIds 白名单谓词。 */
    private static final String[] QUERIES = {
            // searchRanked：推荐排序 + 坐标（LIST_FILTERS + RADIUS_PREDICATE +
            // RELEVANCE_KEYS（keyword 存在时相关度排序，null 时恒等键）+ HEAT_SCORE——
            // 2026-09-01 距离加成移除：坐标仅用于半径筛选，排序不含距离项）
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS + VenueRepository.RADIUS_PREDICATE
                    + " ORDER BY " + VenueRepository.RELEVANCE_KEYS + VenueRepository.HEAT_SCORE
                    + " DESC, v.id DESC",
            // searchRankedNoLocation / searchHeat：LIST_FILTERS + RELEVANCE_KEYS + HEAT_SCORE 排序
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS
                    + " ORDER BY " + VenueRepository.RELEVANCE_KEYS + VenueRepository.HEAT_SCORE + " DESC",
            // searchNearest：LIST_FILTERS + 坐标非空 + RADIUS_PREDICATE + 距离升序
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS
                    + " AND v.latitude IS NOT NULL AND v.longitude IS NOT NULL\n"
                    + VenueRepository.RADIUS_PREDICATE
                    + " ORDER BY " + VenueRepository.DISTANCE_KM + " ASC, v.id ASC",
            // searchHeat / searchNewest 变体 = 前三种的排序/谓词组合，语法已全覆盖，
            // 补 newest 排序（无距离项、无 HEAT_SCORE）
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS
                    + " ORDER BY v.createdAt DESC, v.id DESC",
            // countCitiesByFilters（2026-09-29 搜索结果城市分面）：LIST_FILTERS + 分组聚合。
            // 与上面四条共用同一份 LIST_FILTERS ⇒ 谓词片段的任何语法性回归都会被本数组
            // 的任何一条命中；本条额外覆盖「聚合 + GROUP BY + 别名排序」这一形态。
            "SELECT v.city AS city, COUNT(v) AS venueCount\nFROM Venue v\n"
                    + VenueRepository.LIST_FILTERS
                    + "\nGROUP BY v.city\nORDER BY COUNT(v) DESC, v.city ASC",
            // findAdminPage / findAdminIds（2026-10-03 足迹排序）：两条查询共用 ADMIN_LIST_FILTERS，
            // 拼接形态与注解逐字一致（实体投影分页 + id 投影全量）
            "SELECT v FROM Venue v\n" + VenueRepository.ADMIN_LIST_FILTERS + "\nORDER BY v.id DESC",
            "SELECT v.id FROM Venue v\n" + VenueRepository.ADMIN_LIST_FILTERS + "\nORDER BY v.id DESC",
    };

    @Test
    void listQueryHqlParsesWithoutSyntaxErrors() {
        for (String query : QUERIES) {
            parseOrFail(query);
        }
    }

    /**
     * 全部 {@code @Query} SQL 文本（<b>含 nativeQuery</b>）的括号配平静态校验。
     * <p>
     * <b>为什么需要它（2026-09-19 生产事故根因）</b>：给 {@code findHotVenueIds} 的浏览子查询
     * 追加「内部账号排除」谓词时，新写的
     * {@code AND (vv.user_id IS NULL OR vv.user_id NOT IN :excludedUserIds)} 吃掉了原本用来
     * 闭合 {@code (SELECT ...)} 与 {@code LN(...)} 的两个右括号，而补写时只补回一个 ——
     * <b>少一个右括号</b>。后果：native SQL 无启动期校验（Hibernate 7 已移除
     * {@code validate_native_queries}），只在首次执行时由 MySQL 报
     * {@code SQLSyntaxErrorException}，表现为「首页热门筛选 / 列表接口整体 500」。
     * <p>
     * <b>为什么上面的 HQL 语法测试拦不住</b>：{@link #QUERIES} 覆盖的是 JPQL 列表查询，
     * 而本缺陷在 {@code nativeQuery=true} 的两条查询里——两条路径的校验手段不同（JPQL 有
     * grammar 可解析，native 只能靠真库）。
     * <p>
     * <b>本测试的覆盖方式</b>：反射读取全部 {@code @Query} 的 {@code value}/{@code countQuery}
     * 文本（Spring 在启动时早已把 Java 字符串拼接完成为最终 SQL），对「去掉字符串字面量后」
     * 的文本做括号配平断言。零成本（不加载 Spring 上下文、不连库），且**对 JPQL 与 native
     * 一视同仁**——见 {@code VenueHotVenueIdsSqlTest} 补的语义层真库验证。
     * <p>
     * 局限：只覆盖「括号配平」这一缺陷类（正是本次事故的类别）；列名/别名/参数绑定类型
     * 仍需真库验证。
     */
    @Test
    void allQuerySqlTextsHaveBalancedParentheses() {
        int checked = 0;
        for (java.lang.reflect.Method method : VenueRepository.class.getDeclaredMethods()) {
            org.springframework.data.jpa.repository.Query q =
                    method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
            if (q == null) continue;
            assertBalanced(method.getName() + "#value", q.value());
            if (!q.countQuery().isBlank()) {
                assertBalanced(method.getName() + "#countQuery", q.countQuery());
            }
            checked++;
        }
        assertTrue(checked >= 20, "应扫描到全部 @Query 方法（当前 " + checked + " 个，疑似反射口径失效）");
    }

    /**
     * 「文本块拼接粘连」缺陷类的静态拦截（2026-10-06 线上事故入机器门禁）。
     * <p>
     * <b>根因</b>：Java 文本块（text block）会剥离每行<b>行尾空白</b>，且 {@code """} 后的
     * 换行 + 缩进同样被剥掉。于是
     * <pre>
     * ") - """ + FREE_TIER + """\n ELSE 0 END) * """
     * </pre>
     * 实拼成 {@code ) -2ELSE 0 END) *10}——数字与后续 SQL 关键字<b>粘连</b>。
     * MySQL 词法器把 {@code 2E} 当科学计数法起始 ⇒ {@code SQLSyntaxErrorException}，
     * native SQL 无启动期校验 ⇒ 只能由真实数据库在首次执行时报出来，表现为接口整体 500。
     * <p>
     * <b>为什么括号配平测试拦不住</b>：{@link #allQuerySqlTextsHaveBalancedParentheses}
     * 只管 {@code ()} 数量；{@link #listQueryHqlParsesWithoutSyntaxErrors} 走 HQL
     * ANTLR 词法，Hibernate 会把 {@code 2ELSE} 切成两个 token 而容错通过——但 MySQL 不容错。
     * 换言之<b>同一段拼接文本在 JPQL 侧绿灯、在 MySQL 侧炸</b>，这正是 native 与 JPQL
     * 校验手段不对称的盲区。
     * <p>
     * <b>本测试的判定</b>：拼接完成后的 SQL 文本中不应出现「数字紧跟字母/下划线」
     * （{@code \d[A-Za-z_]}）。SQL 文本里数字与标识符之间<b>永远</b>需要分隔符，
     * 该模式在本仓无合法出现（表别名形如 {@code vv1}/{@code pt} 是字母在前，不匹配）。
     * <p>
     * <b>正确写法（唯一）</b>：拼接点两侧一律显式补空格，
     * {@code + " " + VenueHeatWeights.VISIT_FREE_TIER + " " + """}——
     * 该约定已有两处先例（{@code DancerRepository.PUBLIC_PAGE_ORDER_BY} 2026-08-29 事故
     * 修复、{@code VenueRepository.VIEW_BEHAVIOR} 浏览三项权重），本门禁将其升为
     * 全 {@code @Query} 文本的强制约束。
     */
    @Test
    void noQueryTextHasNumberGluedToFollowingIdentifier() {
        int checked = 0;
        java.util.regex.Pattern glued =
                java.util.regex.Pattern.compile("(?<![A-Za-z_0-9.])[0-9]+[A-Za-z_]");
        for (java.lang.reflect.Method method : VenueRepository.class.getDeclaredMethods()) {
            org.springframework.data.jpa.repository.Query q =
                    method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
            if (q == null) continue;
            assertNotGlued(method.getName() + "#value", q.value(), glued);
            if (!q.countQuery().isBlank()) {
                assertNotGlued(method.getName() + "#countQuery", q.countQuery(), glued);
            }
            checked++;
        }
        assertTrue(checked >= 20, "应扫描到全部 @Query 方法（当前 " + checked + " 个，疑似反射口径失效）");
    }

    /** 同款检查覆盖 {@link VenueRepository} 的公开 SQL 片段常量（被多处查询复用的公式载体）。 */
    @Test
    void sharedSqlFragmentsHaveNoNumberGluedToFollowingIdentifier() {
        java.util.regex.Pattern glued =
                java.util.regex.Pattern.compile("(?<![A-Za-z_0-9.])[0-9]+[A-Za-z_]");
        String[] fragments = {
                VenueRepository.HEAT_SCORE, VenueRepository.HEAT_BEHAVIOR,
                VenueRepository.VIEW_BEHAVIOR, VenueRepository.LIST_FILTERS,
                VenueRepository.RADIUS_PREDICATE, VenueRepository.RELEVANCE_KEYS,
                VenueRepository.DISTANCE_KM, VenueRepository.ADMIN_LIST_FILTERS,
        };
        for (String fragment : fragments) {
            assertNotGlued("fragment", fragment, glued);
        }
    }

    private static void assertNotGlued(String label, String sql, java.util.regex.Pattern glued) {
        java.util.regex.Matcher m = glued.matcher(sql);
        if (m.find()) {
            int from = Math.max(0, m.start() - 60);
            int to = Math.min(sql.length(), m.end() + 60);
            throw new AssertionError("[" + label + "] 数字与后续标识符/关键字粘连（文本块拼接漏空格）：\""
                    + m.group() + "\"\n上下文：…" + sql.substring(from, to).replace("\n", "\\n") + "…\n"
                    + "修法：拼接点两侧显式补空格 + \" \" + 常量 + \" \" + \"\"\"");
        }
    }

    private static void assertBalanced(String label, String sql) {
        int depth = 0;
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // SQL 单引号字面量（'' 为转义的内嵌引号）——其中的括号不参与配平
                inLiteral = !inLiteral;
            } else if (!inLiteral && c == '(') {
                depth++;
            } else if (!inLiteral && c == ')') {
                depth--;
                if (depth < 0) {
                    throw new AssertionError("[" + label + "] 出现多余的右括号（深度转负）\nSQL:\n" + sql);
                }
            }
        }
        if (depth != 0) {
            throw new AssertionError("[" + label + "] 括号未配平：净差 " + depth
                    + "（正 = 少写了右括号，负 = 多写了右括号）\nSQL:\n" + sql);
        }
    }

    private static void parseOrFail(String hql) {
        HqlSyntaxAssertions.assertParses(hql);
    }
}
