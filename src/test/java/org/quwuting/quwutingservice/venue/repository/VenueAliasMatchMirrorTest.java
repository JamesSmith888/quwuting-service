package org.quwuting.quwutingservice.venue.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店别名「三处镜像」一致性静态校验（2026-09-10，零依赖：纯字符串断言 + 注解反射）。
 * <p>
 * <b>为什么需要它</b>：2026-09-07 引入门店别名域时，别名被接进了
 * {@link VenueRepository#RELEVANCE_KEYS}（相关度档位 2）与 {@code suggestByName}
 * （联想中缀兜底），连 {@link VenueRepository#KW_MATCH} 的 javadoc 都改成了
 * 「六字段 + 双别名载体」——<b>唯独 KW_MATCH 的谓词本体漏了</b>。后果是：
 * 列表搜索（单词与多词两条路径）完全搜不到别名，而唯一能命中的 suggest 接口前端
 * 联想浮层早已下线 ⇒ 用户侧零可用路径；更隐蔽的是 RELEVANCE_KEYS 的别名档位
 * 沦为<b>死代码</b>（别名门店进不了结果集，档位再"正确"也无从生效）。
 * <p>
 * 该缺陷是纯文本层面的「注释与谓词不同步」，编译、HQL 语法测试（
 * {@link VenueListQueryHqlSyntaxTest} 只验语法合法性，不看分支是否齐全）全都发现不了
 * ——只能靠这个断言锁住。详见 {@code docs/agents/38-venue-aliases.md} §4.0。
 * <p>
 * <b>判据</b>：增删任何用户检索载体（门店别名 / 同步映射别名 / 未来的新载体），
 * 三处镜像必须同改；其中 KW_MATCH 是<b>唯一的结果集决定方</b>，它漏了 = 用户搜不到，
 * 另两处改得再对也没用。本测试只锁「别名载体在三处都存在」这一条最小事实。
 * <p>
 * 局限：只验载体存在，不验谓词写法正确（EXISTS/ESCAPE/软删条件仍需人工与
 * HQL 语法测试把关）——但"整个分支消失"这一最常见的漏改形态可被完全拦住。
 */
class VenueAliasMatchMirrorTest {

    /** 命中载体在 HQL 中的实体名片段（EXISTS 子查询 FROM 子句） */
    private static final String ALIAS_CARRIER = "FROM VenueAlias";

    @Test
    void kwMatchContainsVenueAliasBranch() {
        assertTrue(VenueRepository.KW_MATCH.contains(ALIAS_CARRIER),
                "KW_MATCH 缺少门店别名命中分支（EXISTS ... FROM VenueAlias）——"
                        + "它是唯一的结果集决定方，漏掉本分支 = 用户按别名完全搜不到店。"
                        + "见 docs/agents/38-venue-aliases.md §4.0");
        // 防复发：同步别名分支（2026-09-02 引入）同样不得被误删——它是「圈内叫法搜得到」
        // 的既有数据载体，与门店别名并行存在，二者是 OR 关系而非替代关系。
        assertTrue(VenueRepository.KW_MATCH.contains("FROM VenueSyncAlias"),
                "KW_MATCH 缺少同步映射别名命中分支（EXISTS ... FROM VenueSyncAlias）——"
                        + "该分支是 2026-09-02 起的既有召回通道，引入门店别名时不得替换掉它");
    }

    @Test
    void relevanceKeysContainsVenueAliasBranch() {
        assertTrue(VenueRepository.RELEVANCE_KEYS.contains(ALIAS_CARRIER),
                "RELEVANCE_KEYS 缺少别名命中档位（别名=身份级匹配，须压过描述里顺带提到）；"
                        + "见 docs/agents/38-venue-aliases.md §4");
    }

    /**
     * suggest 的 @Query 是方法注解而非公开常量，走反射读取（{@code @Query} 为 RUNTIME
     * 保留）。方法签名变化时本测试会以 NoSuchMethodException 直接失败——那是**有意的**：
     * 改名/改参数即意味着「联想接口的命中口径需要重新确认」，不该静默通过。
     */
    @Test
    void suggestByNameContainsVenueAliasBranch() throws Exception {
        Query query = VenueRepository.class
                .getDeclaredMethod("suggestByName", String.class, String.class, Pageable.class)
                .getAnnotation(Query.class);
        assertTrue(query != null, "suggestByName 应为 @Query 注解方法（联想命中口径的唯一载体）");
        assertTrue(query.value().contains(ALIAS_CARRIER),
                "suggestByName 缺少门店别名中缀分支——注意：本接口前端联想浮层已下线，"
                        + "但它作为「命中载体一致」契约的镜像必须与 KW_MATCH 同步演进；"
                        + "见 docs/agents/38-venue-aliases.md §4");
    }
}
