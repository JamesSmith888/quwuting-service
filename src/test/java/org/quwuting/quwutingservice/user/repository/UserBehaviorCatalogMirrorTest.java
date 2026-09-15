package org.quwuting.quwutingservice.user.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户行为<b>事件目录</b>一致性门禁（2026-09-15，零依赖：纯字符串断言 + 注解反射；
 * 不连库、不起 Spring 容器）。与 {@code UserStatsSqlMirrorTest} 互补——
 * 后者守「用户范围谓词 + 日级事实集」，本类守「<b>事件目录 ⟺ 事件级事实集 ⟺ 消费方引用</b>」。
 *
 * <h2>为什么需要它（根因）</h2>
 * 「用户做过什么」在 2026-09-15 前只以 SQL 文本形式存在（两条手写 UNION 串），
 * 于是每加一个消费方（轨迹 / 类型分布 / 活跃时段）都要把表清单与列名<b>再抄一遍</b>；
 * 抄写漂移是纯文本层面的不同步，编译器、HQL 语法测试、启动校验全都发现不了。
 * 本次把行为事实升级为声明式目录 {@link UserBehaviorEvent}，SQL 常量成为它的生成物——
 * 本门禁即那条「生成物校验和」：<b>只改目录（漏改字面量）或只改字面量（漏改目录）
 * 都会立刻红</b>，且失败信息给出的 expected 文本可直接替换。
 *
 * <h2>判据（七类）</h2>
 * <ol>
 *   <li><b>逐字等价</b>：{@code UserStatsSql} / {@code UserBehaviorSql} 的 5 条事实集常量
 *       必须 {@code equals} 目录生成器输出（口径的实际承载物，禁任何手工润色）；</li>
 *   <li><b>成员完整且互斥</b>：每个事件码唯一、表唯一、中文名非空；ACTIVE/PASSIVE/SIGNAL/COLLAB
 *       四档规模锁定（12/4/1/1）——无声增删会改变「活跃」与「噪音」口径；</li>
 *   <li><b>轨迹覆盖全目录</b>：轨迹事实集的事件码集合 = 事件级事实集的事件码集合 =
 *       目录全量（少一个 ⇒ 某类行为从轨迹里消失，且不会有人发现）；</li>
 *   <li><b>敏感列禁入</b>：联系手机号 / 真实姓名 / 微信号 / openId 不得成为轨迹的关联对象或
 *       明细列——轨迹回答「做过什么」，不回答「用户是谁的档案」；</li>
 *   <li><b>消费方只能引用常量</b>：行为统计仓库的每条 {@code @Query} 必须包含目录生成的事实集
 *       片段，且<b>平台级查询必须带</b> {@code USER_SCOPE}、<b>单用户查询必须不带</b>
 *       （口径边界：入口列表过滤、取证式查看不过滤）。</li>
 *   <li><b>目录声明列必须真实存在于 DDL</b>：{@link #catalogColumnsExistInSchema()}——前五类
 *       判据全部作用在<b>文本层</b>（目录 ⟺ 字面量），没有一条能发现「目录把列名写错」。
 *       事故形态：{@code STATUS_REPORT} 声明明细列 {@code reason}，而该列早在 V11 泛化时被
 *       {@code DROP COLUMN} 换成 {@code type}，文本层完全自洽、编译/启动/门禁全绿，
 *       直到管理端点开「行为轨迹」才 500（{@code Unknown column 'reason' in 'field list'}）。</li>
 *   <li><b>派生表列引用必须解析得到</b>：{@link #aliasReferencesResolveToFactColumns()}——
 *       与前六类的差别在于它查的是「<b>同一语句内的名字解析</b>」：派生表 {@code e} 的列名取自
 *       内层 SELECT 的列名（下划线风格），而 {@code AS eventType} 只是本层投影别名，
 *       {@code e.eventType} 这种带前缀写法解析不到（{@code Unknown column 'e.eventType'
 *       in 'order clause'}）。它与第 6 类是两个<b>独立</b>的缺陷类型，会前后脚出现在同一条
 *       SQL 上（前一个先在 field list 阶段炸，把后一个挡住）——**修完一个 500 立即出现
 *       下一个 500，说明「修到第一处报错为止」的验证深度不足**。</li>
 * </ol>
 */
class UserBehaviorCatalogMirrorTest {

    /** 目录四档规模（无声增删会改口径 ⇒ 必须显式改本行并在 docs/agents/35 登记） */
    private static final int ACTIVE_SIZE = 12;
    private static final int PASSIVE_SIZE = 4;
    private static final int SIGNAL_SIZE = 1;
    private static final int COLLAB_SIZE = 1;

    /** 轨迹/明细列禁止出现的用户敏感列（含 openId 的库列名 open_id 与联系人字段） */
    private static final List<String> FORBIDDEN_COLUMNS = List.of(
            "contact_phone", "contact_wechat", "contact_name", "real_name", "open_id", "phone");

    /** 从事实集 fragment 里抽取事件码：{@code 'CODE' AS event_type} */
    private static final Pattern EVENT_CODE = Pattern.compile("'([A-Z_]+)' AS event_type");

    // ── 1. 逐字等价（生成物校验和） ─────────────────────────────────────────────

    @Test
    void catalogGeneratorsMatchSqlLiterals() {
        assertEquals(UserBehaviorEvent.activeFactUnion(), UserStatsSql.ACTIVE_FACT_UNION,
                "活跃事实集字面量与目录生成器不一致——加/删行为表只能改 UserBehaviorEvent，"
                        + "然后把上面 expected 的文本原样替换进 UserStatsSql.ACTIVE_FACT_UNION");
        assertEquals(UserBehaviorEvent.passiveTraceFactUnion(), UserStatsSql.PASSIVE_TRACE_FACT_UNION,
                "被动痕迹事实集字面量与目录生成器不一致（同上：改目录 → 替换字面量）");
        assertEquals(UserBehaviorEvent.allEventFactUnion(), UserBehaviorSql.EVENT_FACT_UNION,
                "事件级事实集（全档）字面量与目录生成器不一致——轨迹/类型分布/时段统计都靠它");
        assertEquals(UserBehaviorEvent.activeEventFactUnion(), UserBehaviorSql.ACTIVE_EVENT_FACT_UNION,
                "事件级事实集（仅主动行为）与目录生成器不一致——"
                        + "「活跃天数/时段」若与目录漂移，会出现两套活跃口径");
        assertEquals(UserBehaviorEvent.eventDetailUnion(), UserBehaviorSql.EVENT_DETAIL_UNION,
                "轨迹事实集（带关联对象/明细）与目录生成器不一致——轨迹会缺事件或错列");
    }

    // ── 2. 成员完整且互斥 ──────────────────────────────────────────────────────

    @Test
    void catalogMembersAreCompleteUniqueAndLabelled() {
        List<UserBehaviorEvent> all = UserBehaviorEvent.all();
        assertEquals(ACTIVE_SIZE, UserBehaviorEvent.of(UserBehaviorEvent.Nature.ACTIVE).size(),
                "主动行为（活跃口径）成员数变化——它直接决定 DAU 与留存的历史数字，"
                        + "必须先在 docs/agents/35 口径表登记「数字变化是预期而非故障」");
        assertEquals(PASSIVE_SIZE, UserBehaviorEvent.of(UserBehaviorEvent.Nature.PASSIVE).size(),
                "被动痕迹成员数变化——噪音判定（注册后从未有任何痕迹）会跟着变宽/变窄");
        assertEquals(SIGNAL_SIZE, UserBehaviorEvent.of(UserBehaviorEvent.Nature.SIGNAL).size(),
                "系统信号档必须恒为单一成员（登录自动打卡）——"
                        + "它撑起管理端「打开」序列，多一个成员会让「打开」与「活跃」再次混淆");
        assertEquals(COLLAB_SIZE, UserBehaviorEvent.of(UserBehaviorEvent.Nature.COLLAB).size(),
                "协作行为档成员数变化——该类事件当前不计入活跃，增删需显式登记");

        Set<String> codes = all.stream().map(UserBehaviorEvent::code).collect(Collectors.toSet());
        Set<String> tables = all.stream().map(UserBehaviorEvent::table).collect(Collectors.toSet());
        assertEquals(all.size(), codes.size(), "事件码必须唯一（重复码会让轨迹按类型筛选时串档）");
        assertEquals(all.size(), tables.size(), "一个行为表只能声明一次事件（重复声明会让事实集重复计数）");
        all.forEach(e -> {
            assertNotNull(e.nature(), e.code() + " 缺少口径档——「算不算活跃」必须有唯一裁决处");
            assertNotNull(e.category(), e.code() + " 缺少分类——轨迹与图例的分组依赖它");
            assertTrue(e.label() != null && !e.label().isBlank(),
                    e.code() + " 缺少中文名——事件文案只允许在目录里定义（消费方硬编码是上次的根因）");
            assertNotNull(e.refKind(), e.code() + " 缺少关联对象类型（无对象必须是 RefKind.NONE）");
            assertEquals(e.detailColumn() == null, e.detailDict() == null,
                    e.code() + " 的 detailColumn 与 detailDict 必须同时存在或同时为空"
                            + "（只有其一 = 明细列没有渲染字典或反之，前端会拿到无法解释的原始值）");
        });
    }

    @Test
    void openSignalIsTheOnlyCheckinSource() {
        UserBehaviorEvent open = UserBehaviorEvent.openSignal();
        assertEquals("qwt_daily_checkins", open.table(),
                "「打开」信号必须来自登录自动打卡表——换表等于换口径，需同步 docs/agents/35");
        assertEquals(UserBehaviorEvent.Nature.SIGNAL, open.nature(),
                "「打开」信号不得升格为主动行为：打卡是登录后自动触发，把它算作活跃正是 2026-09-15"
                        + "修复的那个错误决策（顶卡「近 7 日活跃」一度统计的是「打开过的号」）");
        assertTrue(!UserStatsSql.ACTIVE_FACT_UNION.contains(open.table()),
                "活跃事实集混入了打卡表——回归到「打开 = 活跃」，会与同屏「真实互动」曲线自相矛盾");
        assertTrue(!UserBehaviorSql.ACTIVE_EVENT_FACT_UNION.contains(open.table()),
                "活跃事件级事实集混入了打卡表（同上）");
    }

    // ── 3. 轨迹覆盖全目录 ──────────────────────────────────────────────────────

    @Test
    void timelineFactCoversWholeCatalog() {
        Set<String> catalog = UserBehaviorEvent.all().stream()
                .map(UserBehaviorEvent::code).collect(Collectors.toSet());
        assertEquals(catalog, codesOf(UserBehaviorSql.EVENT_FACT_UNION),
                "事件级事实集缺事件——该行为将不出现在轨迹/类型分布里，且不会有任何报错");
        assertEquals(catalog, codesOf(UserBehaviorSql.EVENT_DETAIL_UNION),
                "轨迹事实集缺事件（同上）；两条由同一目录生成，成员集合必须逐字一致");
        assertEquals(UserBehaviorEvent.of(UserBehaviorEvent.Nature.ACTIVE).stream()
                        .map(UserBehaviorEvent::code).collect(Collectors.toSet()),
                codesOf(UserBehaviorSql.ACTIVE_EVENT_FACT_UNION),
                "活跃事件级事实集成员必须恰好等于目录中的主动行为档");
    }

    @Test
    void everyBranchCarriesCanonicalDayAndTime() {
        UserBehaviorEvent.all().forEach(e -> {
            String day = e.dayColumn() != null ? e.dayColumn() : "DATE(created_at)";
            // 窗口下界的比较列 = 业务日列或原始时间戳列（禁 DATE(created_at) >= ——函数包列会让索引失效）
            String lowerBound = e.dayColumn() != null ? e.dayColumn() : "created_at";
            String kind = e.dayColumn() != null ? "DATE" : "DATETIME";
            String fragment = day + " AS event_day, created_at AS event_time FROM " + e.table()
                    + " WHERE user_id IS NOT NULL AND " + lowerBound
                    + " >= CAST(:sinceDay AS " + kind + ")";
            assertTrue(UserBehaviorSql.EVENT_FACT_UNION.contains(fragment),
                    e.code() + " 的分支缺少「权威日 + 时刻」或窗口下界——"
                            + "权威日必须与日级事实集同源（否则「活跃天数」与看板对不上），"
                            + "窗口下界必须与日列类型匹配且比较的是原始列"
                            + "（写成 DATE(created_at) >= 会让 created_at 索引失效）");
            assertTrue(!UserBehaviorSql.EVENT_FACT_UNION.contains(
                            "DATE(created_at) >= CAST(:sinceDay"),
                    "事实集用 DATE(created_at) 做窗口下界——函数包列会让 created_at 索引失效，"
                            + "12 张行为表逐日扫描时这是最贵的一处写法（口径与 created_at >= 当日 0 点等价）");
        });
    }

    // ── 4. 敏感列禁入 ─────────────────────────────────────────────────────────

    @Test
    void timelineNeverCarriesSensitiveUserColumns() {
        String union = UserBehaviorSql.EVENT_DETAIL_UNION.toLowerCase();
        for (String column : FORBIDDEN_COLUMNS) {
            assertTrue(!union.contains(column),
                    "轨迹事实集带上了敏感列 " + column + " ——轨迹只回答「做过什么」，"
                            + "不回答「用户是谁的档案」（管理端另有专门通道下发必要字段）");
        }
        UserBehaviorEvent.all().forEach(e -> {
            List<String> declared = java.util.stream.Stream
                    .of(e.refColumn(), e.detailColumn())
                    .filter(java.util.Objects::nonNull).toList();
            declared.forEach(column -> assertTrue(!FORBIDDEN_COLUMNS.contains(column.toLowerCase()),
                    e.code() + " 把敏感列 " + column + " 声明为关联对象/明细列"));
        });
    }

    // ── 5. 消费方只能引用常量 ──────────────────────────────────────────────────

    @Test
    void behaviorQueriesComposeCatalogFragments() {
        assertTrue(queryOf("listTimeline", Long.class, LocalDate.class, String.class, int.class)
                        .contains(UserBehaviorSql.EVENT_DETAIL_UNION),
                "轨迹查询必须引用轨迹事实集常量（内联抄写会与目录漂移）");
        for (String method : List.of("listUserEvents", "listPlatformUserTypes",
                "listPlatformUserEventDays")) {
            assertTrue(queryOf(method, longOrIntArgs(method)).contains(UserBehaviorSql.EVENT_FACT_UNION),
                    "行为聚合 " + method + " 必须引用事件级事实集常量");
        }
        for (String method : List.of("listPlatformUserActiveDays", "listPlatformHourly")) {
            assertTrue(queryOf(method, longOrIntArgs(method))
                            .contains(UserBehaviorSql.ACTIVE_EVENT_FACT_UNION),
                    "活跃口径查询 " + method + " 必须引用「仅主动行为」常量的——"
                            + "若改用全档事实集，打卡/被动痕迹会被算进活跃（错误决策回潮）");
        }
    }

    @Test
    void platformQueriesFilterRealUsersButPerUserQueriesDoNot() {
        for (String method : List.of("listPlatformUserTypes", "listPlatformUserActiveDays",
                "listPlatformUserEventDays", "listPlatformHourly")) {
            assertTrue(queryOf(method, longOrIntArgs(method)).contains(UserStatsSql.USER_SCOPE),
                    "平台级统计 " + method + " 必须走统一用户范围谓词——"
                            + "否则 ADMIN 运营号 / test_ 开发号 / 微信审核账号会混进行为分析");
        }
        assertTrue(queryOf("listRealUserJoinedDays").contains(UserStatsSql.USER_SCOPE),
                "分层分母（注册日盘子）必须是真实用户集合，否则分层人数之和 ≠ 总用户数");
        // 口径边界：单用户读取不过滤（入口列表已过滤；运营需要能对已标记账号做取证式查看）
        for (String method : List.of("listTimeline")) {
            assertTrue(!queryOf(method, Long.class, LocalDate.class, String.class, int.class)
                            .contains(UserStatsSql.USER_SCOPE),
                    "单用户轨迹查询不应带用户范围谓词——口径过滤发生在入口列表，"
                            + "此处过滤会让「已标记账号」的页面永远空着（运营无法取证与撤销标记）");
        }
        assertTrue(!queryOf("listUserEvents", Long.class, LocalDate.class)
                        .contains(UserStatsSql.USER_SCOPE),
                "单用户画像查询不应带用户范围谓词（理由同上）");
    }

    // ── 6. 目录 ⟺ DDL（列存在性） ──────────────────────────────────────────────

    /**
     * 目录声明的每一列都必须真实存在于迁移脚本（MySQL 8）定义的表结构里。
     * <p>
     * <b>为什么前五类判据拦不住</b>：它们全部是「目录 ⟺ 字面量」的文本层断言，
     * 而错误列名在文本层是<b>自洽</b>的——目录写 {@code reason}、字面量也写 {@code reason}，
     * 逐字相等，编译、启动校验、门禁全绿。只有 DDL 知道 {@code reason} 已被 V11
     * {@code DROP COLUMN}。故本判据把 {@code db/migration-mysql} 当唯一事实源。
     * <p>
     * 零依赖：只读文件做字符串解析（不连库、不起容器），DDL 形态支持
     * {@code CREATE TABLE} / {@code ALTER TABLE ... ADD|DROP|RENAME|CHANGE COLUMN}，
     * 按 {@code V<序号>} 自然序应用（V1 基线 + 后续 ALTER 累积 = 线上最终结构）。
     */
    @Test
    void catalogColumnsExistInSchema() {
        Map<String, Set<String>> schema = migrationSchema();
        assertTrue(schema.size() >= 30, "迁移脚本只解析出 " + schema.size()
                + " 张表——DDL 解析器已失效，本门禁形同虚设（请校准 MIGRATION_DIRS / 解析规则）");

        UserBehaviorEvent.all().forEach(e -> {
            Set<String> columns = schema.get(e.table());
            assertNotNull(columns, e.code() + " 声明的表 " + e.table()
                    + " 在 db/migration-mysql 的 DDL 里不存在——目录与库结构已分叉");
            declaredColumns(e).forEach(column -> assertTrue(columns.contains(column),
                    e.code() + " 把 " + e.table() + " 的列「" + column
                            + "」声明为日列/关联列/明细列，但该列在迁移脚本里不存在——"
                            + "运行期表现为 Unknown column '" + column + "'（管理端接口 500）；"
                            + "请核对《迁移脚本里的真实列名》后同步改目录与 "
                            + "UserBehaviorSql 字面量"));
        });
    }

    /** 目录声明会落进 SQL 的全部列（时间列恒为 created_at） */
    private static List<String> declaredColumns(UserBehaviorEvent e) {
        return Stream.of(e.dayColumn(), e.refColumn(), e.detailColumn(), e.timeColumn())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** MySQL 迁移脚本目录（源优先；从构建产物运行时回退到 classpath 内副本） */
    private static final List<Path> MIGRATION_DIRS = List.of(
            Path.of("src/main/resources/db/migration-mysql"),
            Path.of("target/classes/db/migration-mysql"));

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "^\\s*DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    /** 列定义行（首 token = 列名）；约束行由 {@code CONSTRAINT_HEAD} 先行排除 */
    private static final Pattern COLUMN_DEF = Pattern.compile("^`?([A-Za-z_][A-Za-z0-9_]*)`?\\s");
    private static final Pattern CONSTRAINT_HEAD = Pattern.compile(
            "^(primary\\s+key|unique|constraint|index|key\\b|foreign\\s+key|check|fulltext|spatial)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * 列级操作：{@code ADD|DROP|RENAME|CHANGE COLUMN x [TO y]}。
     * 负向断言排除 {@code ADD CONSTRAINT|INDEX|UNIQUE|FOREIGN|KEY|CHECK|PRIMARY}——
     * 它们不是列（误判会把约束名当成列名，让门禁放过真正的错列）。
     */
    private static final Pattern COLUMN_OP = Pattern.compile(
            "\\b(ADD|DROP|RENAME|CHANGE)\\s+(?:COLUMN\\s+)?"
                    + "(?!CONSTRAINT\\b|INDEX\\b|UNIQUE\\b|FOREIGN\\b|KEY\\b|CHECK\\b|PRIMARY\\b)"
                    + "`?(\\w+)`?(?:\\s+TO\\s+`?(\\w+)`?)?", Pattern.CASE_INSENSITIVE);

    // ── 7. 派生表列引用（别名解析） ─────────────────────────────────────────────

    /** {@code e.<列>}：对派生表 {@code e}（= 事实集）的引用 */
    private static final Pattern ALIAS_REF = Pattern.compile("\\be\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern AS_ALIAS = Pattern.compile("(?i)\\sAS\\s");
    private static final Pattern LEADING_IDENT = Pattern.compile("^`?([A-Za-z_][A-Za-z0-9_]*)`?");

    /**
     * 本仓所有 {@code e.<列>} 引用都必须落在事实集的<b>产出列</b>内。
     * <p>
     * <b>根因（2026-09-15 事故之二）</b>：派生表 {@code e} 的列名取自内层 SELECT 的<b>列名</b>
     * ——事实集常量里全是下划线风格（{@code event_type} / {@code event_day} / …），而
     * {@code e.event_type AS eventType} 里的 {@code eventType} 只是<b>本层投影别名</b>。
     * 带表前缀的引用（{@code e.eventType}）只解析派生表的真实列名，<b>不参与</b> select 别名解析，
     * 于是 {@code ORDER BY happenedAt DESC, e.eventType} 报
     * {@code Unknown column 'e.eventType' in 'order clause'}（1054）。
     * <p>
     * <b>为什么它与第 6 类判据不同</b>：第 6 类查的是「目录声明的列是否存在于 DDL」（跨文件、
     * 与库结构对齐）；本类查的是「SQL 内部引用是否解析得到」（同一语句内的名字解析）。
     * 两个缺陷同处一条 SQL：{@code reason} 在 field list 阶段先炸，把别名错误挡在后面，
     * 修掉前者才暴露——**修完一个 500 立刻出现下一个 500，说明验证停在「第一处报错」是不够的**，
     * 需要按结构（列存在性 + 名字解析）分类各设一条判据。
     * <p>
     * 注意判据的边界：合法形态是「内层真的产出了这个列名」。同仓 {@code UserRetentionRepository}
     * 的 {@code c.cohortDay} / {@code s.newActive} 之所以合法，是因为内层子查询显式写了
     * {@code AS cohortDay} / {@code AS newActive}；而事实集常量由目录生成、列名恒为下划线风格，
     * 故外层只能引用下划线列名。
     */
    @Test
    void aliasReferencesResolveToFactColumns() {
        Set<String> produced = new LinkedHashSet<>(producedColumns(UserBehaviorSql.EVENT_DETAIL_UNION));
        produced.addAll(producedColumns(UserBehaviorSql.EVENT_FACT_UNION));

        int queries = 0;
        for (Method method : UserBehaviorRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            queries++;
            Matcher ref = ALIAS_REF.matcher(query.value());
            while (ref.find()) {
                String column = ref.group(1);
                assertTrue(produced.contains(column),
                        "UserBehaviorRepository#" + method.getName() + " 引用了 e." + column
                                + "，但它不是事实集的产出列（" + produced + "）——派生表列名取自内层 SELECT 的列名，"
                                + "外层 AS " + column + " 只是投影别名，带 e. 前缀的引用不参与别名解析 ⇒ "
                                + "MySQL 1054 Unknown column（order clause）");
            }
        }
        assertTrue(queries >= 7, "行为统计仓的 @Query 数量变为 " + queries
                + "（原 7 条）——增删查询须同轮确认口门口径与 docs/agents/35");
    }

    /** 解析事实集第一个分支的产出列名（= 派生表 {@code e} 的真实列名） */
    private static Set<String> producedColumns(String union) {
        String first = union.split("\nUNION ALL ", 2)[0];
        String projection = first.substring(first.indexOf("SELECT") + "SELECT".length());
        int from = projection.toUpperCase(Locale.ROOT).indexOf(" FROM ");
        if (from >= 0) {
            projection = projection.substring(0, from);
        }
        Set<String> columns = new LinkedHashSet<>();
        for (String part : projection.split(",")) {
            String piece = part.trim();
            Matcher as = AS_ALIAS.matcher(piece);
            int after = -1;
            while (as.find()) {
                after = as.end();
            }
            Matcher ident = LEADING_IDENT.matcher(after >= 0 ? piece.substring(after).trim() : piece);
            assertTrue(ident.find(), "无法解析事实集投影片段：" + piece);
            columns.add(ident.group(1));
        }
        return columns;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** 各方法的参数签名（写成显式表，签名变化 ⇒ 门禁红 ⇒ 提示确认口径，同既有约定） */
    private static Class<?>[] longOrIntArgs(String method) {
        return switch (method) {
            case "listUserEvents" -> new Class<?>[]{Long.class, LocalDate.class};
            case "listPlatformUserTypes", "listPlatformUserActiveDays", "listPlatformHourly" ->
                    new Class<?>[]{LocalDate.class};
            case "listPlatformUserEventDays" -> new Class<?>[]{LocalDate.class, String.class};
            default -> throw new IllegalArgumentException("未登记的方法签名：" + method);
        };
    }

    /** 反射读取 {@code UserBehaviorRepository} 上的 {@code @Query}（RUNTIME 保留） */
    private static String queryOf(String method, Class<?>... params) {
        try {
            Query query = UserBehaviorRepository.class.getDeclaredMethod(method, params)
                    .getAnnotation(Query.class);
            assertNotNull(query, "UserBehaviorRepository#" + method
                    + " 应为 @Query 注解方法（行为统计必须写成可校验的原生 SQL）");
            return query.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("UserBehaviorRepository#" + method
                    + " 签名已变化——请同步确认本门禁与 docs/agents/35 的行为分析口径表", e);
        }
    }

    /** 抽取事实集里的全部事件码 */
    private static Set<String> codesOf(String union) {
        Matcher matcher = EVENT_CODE.matcher(union);
        Set<String> codes = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }

    // ── DDL 解析（零依赖：只读迁移脚本，不连库） ─────────────────────────────────

    /** 按自然序应用全部迁移语句，得到「线上最终结构」的表 → 列集合 */
    private static Map<String, Set<String>> migrationSchema() {
        Map<String, Set<String>> schema = new TreeMap<>();
        for (Path file : migrationFiles()) {
            String sql;
            try {
                sql = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取迁移脚本失败：" + file, e);
            }
            for (String statement : stripComments(sql).split(";")) {
                applyStatement(statement, schema);
            }
        }
        return schema;
    }

    private static List<Path> migrationFiles() {
        Path dir = MIGRATION_DIRS.stream().filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new AssertionError("找不到 MySQL 迁移目录（试过 " + MIGRATION_DIRS + "）"));
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(UserBehaviorCatalogMirrorTest::migrationOrder))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列举迁移脚本失败：" + dir, e);
        }
    }

    /** {@code V12__x.sql} → 12（同号按文件名字典序，保证顺序稳定） */
    private static int migrationOrder(Path path) {
        Matcher matcher = Pattern.compile("^V(\\d+)").matcher(path.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static void applyStatement(String statement, Map<String, Set<String>> schema) {
        String stmt = statement.trim();
        if (stmt.isEmpty()) {
            return;
        }
        if (CREATE_TABLE.matcher(stmt).find()) {
            collectCreatedColumns(stmt, schema);
            return;
        }
        Matcher drop = DROP_TABLE.matcher(stmt);
        if (drop.find()) {
            schema.remove(drop.group(1));
            return;
        }
        Matcher alter = ALTER_TABLE.matcher(stmt);
        if (!alter.find()) {
            return;
        }
        Set<String> columns = schema.computeIfAbsent(alter.group(1), k -> new LinkedHashSet<>());
        Matcher op = COLUMN_OP.matcher(stmt.substring(alter.end()));
        while (op.find()) {
            String action = op.group(1).toUpperCase(Locale.ROOT);
            String column = op.group(2);
            String renamed = op.group(3);
            switch (action) {
                case "ADD" -> columns.add(column);
                case "DROP" -> columns.remove(column);
                case "RENAME", "CHANGE" -> {
                    columns.remove(column);
                    if (renamed != null) {
                        columns.add(renamed);
                    }
                }
                default -> throw new AssertionError("未登记的列级操作：" + action);
            }
        }
    }

    /** 解析 {@code CREATE TABLE <t> ( ... )} 的列定义（跳过约束行；括号配对考虑字符串字面量） */
    private static void collectCreatedColumns(String stmt, Map<String, Set<String>> schema) {
        Matcher head = CREATE_TABLE.matcher(stmt);
        if (!head.find()) {
            return;
        }
        int open = stmt.indexOf('(', head.end());
        if (open < 0) {
            return;
        }
        int depth = 0;
        boolean inQuote = false;
        int close = -1;
        for (int i = open; i < stmt.length(); i++) {
            char c = stmt.charAt(i);
            if (inQuote) {
                inQuote = c != '\'';
                continue;
            }
            if (c == '\'') {
                inQuote = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return;
        }
        Set<String> columns = schema.computeIfAbsent(head.group(1), k -> new LinkedHashSet<>());
        for (String rawLine : stmt.substring(open + 1, close).split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || CONSTRAINT_HEAD.matcher(line).find()) {
                continue;
            }
            Matcher column = COLUMN_DEF.matcher(line);
            if (column.find()) {
                columns.add(column.group(1));
            }
        }
    }

    /** 去掉 {@code --} 行注释与 {@code /* *}{@code /} 块注释（DDL 里的注释不承载结构信息） */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inBlock = false;
        for (String rawLine : sql.split("\n")) {
            String line = rawLine;
            if (inBlock) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                line = line.substring(end + 2);
                inBlock = false;
            }
            int blockStart = line.indexOf("/*");
            if (blockStart >= 0) {
                int blockEnd = line.indexOf("*/", blockStart + 2);
                if (blockEnd >= 0) {
                    line = line.substring(0, blockStart) + line.substring(blockEnd + 2);
                } else {
                    line = line.substring(0, blockStart);
                    inBlock = true;
                }
            }
            int dash = line.indexOf("--");
            if (dash >= 0) {
                line = line.substring(0, dash);
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
