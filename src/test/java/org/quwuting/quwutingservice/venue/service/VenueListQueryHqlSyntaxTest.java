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

    /** 与 VenueRepository @Query 拼接方式一致的完整 SELECT 语句（全部 7 个列表变体）。 */
    private static final String[] QUERIES = {
            // searchRanked：推荐排序 + 坐标（LIST_FILTERS + RADIUS_PREDICATE + 纯 HEAT_SCORE——
            // 2026-09-01 距离加成移除：坐标仅用于半径筛选，排序不含距离项）
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS + VenueRepository.RADIUS_PREDICATE
                    + " ORDER BY " + VenueRepository.HEAT_SCORE + " DESC, v.id DESC",
            // searchRankedNoLocation / searchHeat：LIST_FILTERS + HEAT_SCORE 排序
            "SELECT v FROM Venue v\n" + VenueRepository.LIST_FILTERS
                    + " ORDER BY " + VenueRepository.HEAT_SCORE + " DESC",
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
