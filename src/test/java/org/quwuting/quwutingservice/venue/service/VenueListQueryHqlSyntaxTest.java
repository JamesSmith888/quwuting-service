package org.quwuting.quwutingservice.venue.service;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
import org.junit.jupiter.api.Test;
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
        HqlLexer lexer = new HqlLexer(CharStreams.fromString(hql));
        HqlParser parser = new HqlParser(new CommonTokenStream(lexer));
        // ANTLR 默认错误策略会尝试恢复（只打印不抛）——改为收集语法错误并失败，
        // 让语法性回归在测试层直接显形（与 Hibernate 自身 parseHql 的 fail-fast 语义一致）
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                throw new AssertionError("HQL 语法错误 L" + line + ":" + charPositionInLine
                        + " -> " + msg + "\nSQL:\n" + hql, e);
            }
        });
        parser.statement();
    }
}
