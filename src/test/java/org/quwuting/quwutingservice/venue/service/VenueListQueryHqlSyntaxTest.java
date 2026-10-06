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

    private static void assertNotGlued(String label, String rawSql, java.util.regex.Pattern glued) {
        // 同 assertAliasesDeclared：注释内的点号/数字不是语法，先剥除
        String sql = stripSqlComments(rawSql);
        java.util.regex.Matcher m = glued.matcher(sql);
        if (m.find()) {
            int from = Math.max(0, m.start() - 60);
            int to = Math.min(sql.length(), m.end() + 60);
            throw new AssertionError("[" + label + "] 数字与后续标识符/关键字粘连（文本块拼接漏空格）：\""
                    + m.group() + "\"\n上下文：…" + sql.substring(from, to).replace("\n", "\\n") + "…\n"
                    + "修法：拼接点两侧显式补空格 + \" \" + 常量 + \" \" + \"\"\"");
        }
    }

    /**
     * 「native SQL 别名作用域」缺陷类的静态拦截（2026-10-06 第二次事故入机器门禁）。
     * <p>
     * <b>根因</b>：{@code countHeatCounters} 的到访项写
     * {@code CASE WHEN v.status IN ('OPEN','CLOSED')}，但该查询是<b>纯标量子查询、
     * 没有任何 FROM 子句</b>（24 列全是 {@code (SELECT ...)}）——{@code v} 别名在此作用域
     * 内不存在 ⇒ MySQL {@code Unknown column 'v.status' in 'field list'} ⇒
     * {@code GET /venues/{id}/heat} 详情热度页整体 500。来源 = 从 {@code findHotVenueIds}
     * <b>复制粘贴</b>（那边有 {@code FROM qwt_venues v}，别名成立）。
     * <p>
     * <b>为什么既有门禁全都拦不住</b>：括号配平（本缺陷不影响）、HQL grammar（本缺陷在
     * native 路径，JPQL 镜像里 {@code v} 是合法的 {@code FROM Venue v}）、
     * 上一条「数字粘连」形态检查（本缺陷是<b>存在的</b>别名，不是粘连的 token）——
     * 三条门禁全绿而生产 500。native SQL 无启动期校验，只有真实数据库会拒绝。
     * <p>
     * <b>本测试的判定</b>：对每条 {@code nativeQuery=true} 的 SQL，收集
     * <b>本查询自身 FROM/JOIN 声明的表别名</b>，再断言正文里出现的
     * {@code x.y} 形式的限定名，其 {@code x} 都在声明集合内。
     * 子查询各自声明的别名（{@code vv/f/p/ti/r/pt/m/l} 等）在各自作用域内有效，
     * 实现按括号深度分层收集，故不会误报。
     * <p>
     * <b>已知豁免</b>：{@code a1_0} 等 Hibernate 生成的别名不出现于源码文本；
     * 函数名（{@code COUNT}/{@code COALESCE} 等）后不接 {@code .}，不参与判定。
     * <p>
     * <b>扫描范围 = 全仓全部 Repository</b>（不只 {@code VenueRepository}）：缺陷类与
     * 业务域无关，藏在哪个仓都同样只会在生产执行期炸。
     */
    @Test
    void noNativeQueryReferencesUndeclaredAlias() throws Exception {
        int checked = 0;
        for (Class<?> repo : allRepositoryClasses()) {
            for (java.lang.reflect.Method method : repo.getDeclaredMethods()) {
                org.springframework.data.jpa.repository.Query q =
                        method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
                if (q == null || !q.nativeQuery()) continue;
                String label = repo.getSimpleName() + "#" + method.getName();
                assertAliasesDeclared(label + "#value", q.value());
                if (!q.countQuery().isBlank()) {
                    assertAliasesDeclared(label + "#countQuery", q.countQuery());
                }
                checked++;
            }
        }
        assertTrue(checked >= 20, "应扫描到全仓 native @Query（当前 " + checked + " 个，疑似反射口径失效）");
    }

    /** 扫描 classpath 下全部 Spring Data Repository 接口（由 Spring 的包扫描约定界定）。 */
    private static java.util.List<Class<?>> allRepositoryClasses() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(
                System.getProperty("user.dir"), "target", "classes");
        java.util.List<Class<?>> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<java.nio.file.Path> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            java.nio.file.Path dir = queue.poll();
            try (java.util.stream.Stream<java.nio.file.Path> children = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path child : children.toList()) {
                    if (java.nio.file.Files.isDirectory(child)) {
                        queue.add(child);
                    } else {
                        String name = child.getFileName().toString();
                        if (!name.endsWith("Repository.class") || name.contains("$$")) continue;
                        String cls = child.toString()
                                .substring(root.toString().length() + 1)
                                .replace(java.io.File.separatorChar, '.')
                                .replaceAll("\\.class$", "");
                        try {
                            Class<?> c = Class.forName(cls, false,
                                    VenueListQueryHqlSyntaxTest.class.getClassLoader());
                            // 只认接口 + 确实继承 Repository 的（排除同名普通接口）
                            if (c.isInterface()
                                    && org.springframework.data.repository.Repository.class.isAssignableFrom(c)) {
                                found.add(c);
                            }
                        } catch (Throwable ignored) {
                            // 依赖不全的类跳过（本门禁只做静态文本检查，不需加载成功）
                        }
                    }
                }
            }
        }
        assertTrue(found.size() >= 10,
                "应扫描到全仓 Repository 接口（当前 " + found.size() + " 个，扫描口径可能失效）");
        return found;
    }

    /**
     * 逐括号深度收集「FROM/JOIN 表名 别名」声明，再断言限定名前缀均已声明。
     * <p>
     * <b>两趟扫描（不可改成单趟）</b>：SQL 的求值顺序是 FROM 先于 SELECT 列表，
     * 文本顺序上 {@code SELECT vv.status FROM qwt_venues vv} 里的 {@code vv} 出现在
     * FROM <b>之前</b>——单趟按文本顺序判定会把合法写法误报（门禁自身误报，
     * 2026-10-06）。故第一趟只收集声明，第二趟才判定限定名。
     * <p>
     * <b>可见性方向</b>：关联子查询可见<b>外层</b>别名，故限定名合法当且仅当它出现在
     * 「本层 ∪ 任意外层」的声明集合中。
     */
    private static void assertAliasesDeclared(String label, String rawSql) {
        // 注释剥除：native SQL 里带 `--` 行注释（本仓用作枚举语义说明，注释文本含
        // `PointsGateTargetType.DANCER_CONTACT` 这类点号串，会被误判为限定名），
        // 替换为等长空格以保持括号深度与偏移不变（DancerRepository 误报，2026-10-06）
        String sql = stripSqlComments(rawSql);
        int maxDepth = maxParenDepth(sql);
        // declaredAt[d] = 深度 d 上 FROM/JOIN 声明的别名集合（d=0 为最外层）
        java.util.List<java.util.Set<String>> declaredAt = new java.util.ArrayList<>();
        for (int d = 0; d <= maxDepth; d++) declaredAt.add(new java.util.HashSet<>());

        // ── 第一趟：收集声明 ──
        int depth = 0;
        int i = 0;
        // 记录每个右括号闭合时，若其后紧跟标识符且该标识符出现在 JOIN/,) 之后，
        // 即为派生表别名 `) alias`（形如 LEFT JOIN (SELECT ...) f）——需登记到
        // **该派生表所在层**（= 右括号闭合后的深度），否则 countDailyTrends 一族
        // 的 f/uf/v/vl/vs/vq/pr/nr/pt 会被误判为未声明（门禁自身误报，2026-10-06）。
        java.util.Map<Integer, String> aliasAfterParen = new java.util.HashMap<>();
        int pendingParenDepth = -1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; i++; continue; }
            if (c == ')') {
                depth--;
                pendingParenDepth = depth;
                i++;
                continue;
            }
            if (matchesKeywordAt(sql, i, "FROM") || matchesKeywordAt(sql, i, "JOIN")) {
                // FROM/JOIN 后可能跟两种形态：
                //   ① `) alias`（派生表，别名在右括号之后）
                //   ② `table [AS] alias`（物理表）
                int j = i + 4;
                while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
                if (j < sql.length() && sql.charAt(j) == ')') {
                    pendingParenDepth = -1;   // 已在上面登记，此处不重复处理
                    i = j;
                    continue;
                }
                // 跳过库名/表名（可含 a.b_c）
                while (j < sql.length() && (isIdentChar(sql.charAt(j)) || sql.charAt(j) == '.')) j++;
                while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
                if (matchesKeywordAt(sql, j, "AS")) {
                    j += 2;
                    while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
                }
                int aliasStart = j;
                while (j < sql.length() && isIdentChar(sql.charAt(j))) j++;
                if (j > aliasStart) {
                    String alias = sql.substring(aliasStart, j);
                    if (!isSqlKeyword(alias)) {
                        declaredAt.get(Math.min(depth, maxDepth)).add(alias.toLowerCase());
                    }
                }
                i = j;
                continue;
            }
            // 派生表别名：`GROUP BY day) f ON f.day = d.day` 中的 f
            if (pendingParenDepth >= 0
                    && (Character.isLetter(c) || c == '_')
                    && isDerivedAliasPosition(sql, i)) {
                int start = i;
                int k = i;
                while (k < sql.length() && isIdentChar(sql.charAt(k))) k++;
                String alias = sql.substring(start, k);
                if (!isSqlKeyword(alias)) {
                    declaredAt.get(Math.min(pendingParenDepth, maxDepth)).add(alias.toLowerCase());
                }
                i = k;
                pendingParenDepth = -1;
                continue;
            }
            if (!Character.isWhitespace(c)) pendingParenDepth = -1;
            i++;
        }

        // ── 第二趟：判定限定名（本层 ∪ 任意外层可见）──
        depth = 0;
        i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; i++; continue; }
            if (c == ')') { depth--; i++; continue; }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < sql.length() && isIdentChar(sql.charAt(i))) i++;
                if (i < sql.length() && sql.charAt(i) == '.'
                        && i + 1 < sql.length()
                        && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    // 全限定枚举字面量（org.quwuting...PointsGateTargetType.VENUE，
                    // native SQL 里表示 HQL 枚举值的字符串约定）——前缀含 '.' 段，
                    // 非表别名，跳过判定
                    if (isFullyQualifiedName(sql, start)) { i++; continue; }
                    String prefix = sql.substring(start, i).toLowerCase();
                    boolean visible = false;
                    for (int d = Math.min(depth, maxDepth); d >= 0; d--) {
                        if (declaredAt.get(d).contains(prefix)) { visible = true; break; }
                    }
                    if (!isSqlKeyword(prefix) && !visible) {
                        int from = Math.max(0, start - 60);
                        int to = Math.min(sql.length(), i + 60);
                        throw new AssertionError("[" + label + "] 限定名 " + prefix
                                + ".… 引用了本作用域未声明的表别名（native SQL 别名作用域缺陷，"
                                + "MySQL 会报 Unknown column）\n上下文：…"
                                + sql.substring(from, to).replace("\n", "\\n") + "…\n"
                                + "修法：跨查询复制片段后核对别名作用域；本查询无 FROM 时用"
                                + " (SELECT vv.status FROM qwt_venues vv WHERE vv.id = :xxx) 形式自声明");
                    }
                }
                continue;
            }
            i++;
        }
    }

    /**
     * 剥除 SQL 注释（{@code --} 行注释 / {@code /*} *}{@code /} 块注释 / {@code #} 行注释），
     * <b>用等长空格替换</b>——保持字符偏移与括号计数完全不变，使报错上下文仍指向原文位置。
     * 同时跳过单引号字符串字面量内部（其内的 {@code --} 与点号不是语法）。
     */
    private static String stripSqlComments(String sql) {
        char[] out = sql.toCharArray();
        boolean inLiteral = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            if (c == '\'') { inLiteral = !inLiteral; continue; }
            if (inLiteral) continue;
            if (c == '-' && i + 1 < out.length && out[i + 1] == '-') {
                while (i < out.length && out[i] != '\n') { out[i] = ' '; i++; }
                continue;
            }
            if (c == '#') {
                while (i < out.length && out[i] != '\n') { out[i] = ' '; i++; }
                continue;
            }
            if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? out.length : end + 2;
                for (int k = i; k < stop; k++) {
                    if (out[k] != '\n') out[k] = ' ';
                }
                i = stop - 1;
            }
        }
        return new String(out);
    }

    /**
     * 从 start 处起是否为一个<b>全限定名</b>（含至少两段 {@code a.b}，形如
     * {@code org.quwuting.quwutingservice.points.enums.PointsTargetType.VENUE}）。
     * native SQL 用全限定枚举名表示 HQL 枚举值字面量（注解须编译期常量、无法引
     * 用枚举），其首段是包名而非表别名，<b>必须排除</b>（否则 DancerRepository
     * {@code findPublicPage} 误报，2026-10-06）。
     */
    private static boolean isFullyQualifiedName(String sql, int start) {
        int segments = 1;
        int i = start;
        while (i < sql.length() && (isIdentChar(sql.charAt(i)) || sql.charAt(i) == '.')) {
            if (sql.charAt(i) == '.') segments++;
            i++;
        }
        return segments >= 3;   // 包名至少两段 + 类名 + 枚举常量
    }

    private static int maxParenDepth(String sql) {
        int depth = 0, max = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') { depth++; max = Math.max(max, depth); }
            else if (c == ')') depth--;
        }
        return max;
    }

    /**
     * 判定位置 i 处的标识符是否为「派生表别名」——即右括号闭合后紧跟的别名
     * （{@code ) alias}）。后随形态可以是 {@code ON}（JOIN 派生表）、{@code WHERE} /
     * {@code GROUP BY} / {@code ORDER BY}（FROM 派生表）等多种，故判据是
     * 「右括号后紧跟的、<b>非 SQL 关键字</b>的标识符」——
     * {@code ) AS day}（列别名，AS 是关键字）由此天然排除。
     * <p>
     * 覆盖两种真实写法：{@code LEFT JOIN (SELECT ...) f ON ...}（countDailyTrends 一族）
     * 与 {@code FROM (SELECT ...) t WHERE t.rn = 1}（VenuePresenceConsentRepository）。
     */
    private static boolean isDerivedAliasPosition(String sql, int i) {
        int k = i;
        while (k < sql.length() && isIdentChar(sql.charAt(k))) k++;
        String token = sql.substring(i, k);
        if (isSqlKeyword(token)) return false;
        while (k < sql.length() && Character.isWhitespace(sql.charAt(k))) k++;
        if (k >= sql.length()) return true;                        // 末尾
        // 后随关键字：ON / WHERE / GROUP / ORDER / LIMIT / UNION / LEFT / INNER …
        // （空白已跳过，故可跨行形态 `) f\n JOIN qwt_users u`）
        return matchesKeywordAt(sql, k, "ON")
                || matchesKeywordAt(sql, k, "WHERE")
                || matchesKeywordAt(sql, k, "GROUP")
                || matchesKeywordAt(sql, k, "ORDER")
                || matchesKeywordAt(sql, k, "LIMIT")
                || matchesKeywordAt(sql, k, "UNION")
                || matchesKeywordAt(sql, k, "LEFT")
                || matchesKeywordAt(sql, k, "INNER")
                || matchesKeywordAt(sql, k, "CROSS")
                || matchesKeywordAt(sql, k, "JOIN")
                || sql.charAt(k) == ',' || sql.charAt(k) == ')';
    }

    private static boolean matchesKeywordAt(String sql, int i, String keyword) {
        if (!sql.regionMatches(true, i, keyword, 0, keyword.length())) return false;
        int after = i + keyword.length();
        boolean beforeOk = i == 0 || !isIdentChar(sql.charAt(i - 1));
        boolean afterOk = after >= sql.length() || !isIdentChar(sql.charAt(after));
        return beforeOk && afterOk;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final java.util.Set<String> SQL_KEYWORDS = java.util.Set.of(
            "select", "from", "where", "and", "or", "not", "in", "is", "null", "as", "join",
            "left", "right", "inner", "outer", "cross", "on", "group", "by", "order", "having",
            "case", "when", "then", "else", "end", "distinct", "count", "sum", "avg", "min", "max",
            "coalesce", "greatest", "least", "if", "ifnull", "nullif", "cast", "date", "day",
            "interval", "asc", "desc", "limit", "offset", "union", "all", "exists", "between",
            "like", "escape", "insert", "update", "delete", "set", "values", "into", "true",
            "false", "div", "mod", "current_date", "now", "ln", "abs", "round", "ceil", "floor",
            "char", "concat", "substring", "length", "for", "force", "use", "duplicate", "key");

    private static boolean isSqlKeyword(String token) {
        return SQL_KEYWORDS.contains(token.toLowerCase());
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
