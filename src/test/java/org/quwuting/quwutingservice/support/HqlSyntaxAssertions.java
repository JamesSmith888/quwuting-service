package org.quwuting.quwutingservice.support;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;

/**
 * JPQL/HQL <b>语法级</b>断言（不依赖数据库，2026-10-03 自 {@code VenueListQueryHqlSyntaxTest} 提取为共享工具）。
 * <p>
 * Spring Data {@code @Query} 的 JPQL 字符串懒校验——首次执行时 Hibernate 才解析，启动期覆盖不到；
 * 凡由字符串常量拼接而成的查询（共享谓词片段），都应把完整拼接文本交给本工具解析一次，
 * 让语法性回归在普通 {@code mvn test} 里失败。局限：仅语法层，实体名/属性名仍需真实库验证。
 */
public final class HqlSyntaxAssertions {

    private HqlSyntaxAssertions() {
    }

    /** 解析失败即抛 AssertionError（ANTLR 默认错误策略会尝试恢复、只打印不抛——此处改为 fail-fast） */
    public static void assertParses(String hql) {
        HqlLexer lexer = new HqlLexer(CharStreams.fromString(hql));
        HqlParser parser = new HqlParser(new CommonTokenStream(lexer));
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
