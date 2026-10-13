package org.quwuting.quwutingservice.spend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户侧账本总览「口径方向完整性」门禁（2026-10-13，零依赖：注解反射 + 字符串断言；
 * 不连库、不起 Spring 容器）。与 {@code SpendStatsScopeMirrorTest} /
 * {@code UserStatsSqlMirrorTest} 同族。
 *
 * <h2>为什么需要它（根因）</h2>
 * overview 的 5 条聚合于 2026-10-13 从「恒 EXPENSE」改为「按 direction 参数」
 * （客人看支出、舞伴看收入，44 号 §42）。这次改动有一类<b>静默失效</b>形态：
 * 某一条 SQL 漏加 {@code direction} 过滤（或后续迭代里被改回 {@code 'EXPENSE'}
 * 字面量）——该维度数字会混入两个方向的账目，<b>不会有任何报错</b>
 * （查询正常返回、接口 200），而统计页看起来"就是这几个数"。
 * 5 条里任意一条漏 = 一份自相矛盾的统计（如：总额是收入、分类图是支出）。
 *
 * <h2>判据（两条）</h2>
 * <ol>
 *   <li><b>5 个聚合的 SQL 全部含 {@code direction = :direction} 命名参数绑定</b>
 *       （sumByMonth 用 {@code CASE WHEN} 形态、其余用 {@code WHERE AND} 形态，
 *       语义等价——断言只认"绑定了命名参数"这一件事）；</li>
 *   <li><b>每个方法签名都带 {@code @Param("direction")}</b>——有 {@code :direction}
 *       占位而无 {@code @Param} 绑定会在<b>运行期</b>抛异常，是另一类
 *       "编译全绿、调用才炸"。</li>
 * </ol>
 *
 * <p><b>局限</b>：断言作用在 SQL 文本层，<b>不执行 SQL</b>（项目验证深度红线，不连库）。
 * 新增 overview 聚合方法必须同步纳入 {@link #OVERVIEW_METHODS} 清单——方法改名时
 * {@link #overviewMethod} 会显式失败，不会静默漏检。
 */
class SpendOverviewDirectionMirrorTest {

    /** overview 的全部聚合方法（2026-10-13 起的口径方向契约面） */
    private static final List<String> OVERVIEW_METHODS = List.of(
            "sumByMonth", "sumByCategory", "sumByVenueTop", "sumUnlinked", "sumByMonthTrend");

    @Test
    void everyOverviewQueryFiltersByDirectionParam() {
        for (String name : OVERVIEW_METHODS) {
            Query query = overviewMethod(name).getAnnotation(Query.class);
            if (query == null || query.value() == null || query.value().isEmpty()) {
                throw new AssertionError(name + " 缺少 @Query 注解或 SQL 为空——"
                        + "overview 聚合必须保持显式 SQL（口径可审计；删注解会让本门禁静默跳过）");
            }
            assertTrue(query.value().contains("direction = :direction"),
                    name + " 的 SQL 未按 direction 参数过滤——漏一条 = 该维度数字混入两个方向"
                            + "（统计自相矛盾），且静默无报错");
            assertTrue(query.nativeQuery(),
                    name + " 必须保持 nativeQuery（聚合 SQL 含 MySQL 特有函数）");
        }
    }

    @Test
    void everyOverviewMethodBindsDirectionParam() {
        for (String name : OVERVIEW_METHODS) {
            Method method = overviewMethod(name);
            boolean bound = false;
            for (Parameter parameter : method.getParameters()) {
                Param param = parameter.getAnnotation(Param.class);
                if (param != null && "direction".equals(param.value())) {
                    bound = true;
                    break;
                }
            }
            assertTrue(bound, name + " 缺 @Param(\"direction\") 绑定——"
                    + "有 :direction 占位而无绑定会在调用时抛异常（编译全绿、调用才炸）");
        }
    }

    /** 按名取方法（找不到即失败——改名 / 删除必须同步本门禁，禁静默漏检） */
    private static Method overviewMethod(String name) {
        return Arrays.stream(SpendEntryRepository.class.getDeclaredMethods())
                .filter((m) -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SpendEntryRepository 找不到方法 " + name + "（改名 / 删除须同步本门禁清单）"));
    }
}
