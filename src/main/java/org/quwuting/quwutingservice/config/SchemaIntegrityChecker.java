package org.quwuting.quwutingservice.config;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 启动时 Schema 完整性检查（fail-fast）。
 * <p>
 * 2026-08-04 迁移事故后确立：DB 迁近区时用 DataGrip 拖拽复制表结构，
 * 索引/唯一约束保留了，但 IDENTITY（序列）、主键、NOT NULL 全部丢失。
 * 损坏后的库表现出极强的欺骗性：
 * <ul>
 *   <li>Hibernate {@code ddl-auto: validate} 只校验表/列存在与类型，
 *       <b>不校验主键/identity/NOT NULL</b>，启动期毫无告警；</li>
 *   <li>读/更新路径不依赖生成主键，一切正常；</li>
 *   <li>首个 IDENTITY 插入（如编辑场所触发的状态变迁日志）才抛
 *       {@code AssertionFailure: null identifier}——而浏览记录 upsert
 *       这类不回读主键的写入已经在静默积累 id=NULL 的脏数据。</li>
 * </ul>
 * 本检查器把这类静默损坏转化为启动期的明确失败：
 * 基于 JPA 元模型自动枚举全部实体表（零硬编码表名），单次目录查询
 * 校验每张表的「主键存在且为主键列、主键列 NOT NULL、主键列具备
 * IDENTITY 或序列默认值」，任一缺失即抛异常拒绝启动——主键机制损坏时
 * 一切写入必然失败或产生脏数据，早死优于静默损坏。
 * <p>
 * 仅对 PostgreSQL 生效；其他数据库（如测试用 H2）跳过。
 * 修复指引见 AGENTS.md「Schema 演进与数据库完整性」与
 * {@code src/main/resources/db/repair-schema-identity.sql}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaIntegrityChecker implements ApplicationRunner {

    private static final String POSTGRESQL_PRODUCT = "PostgreSQL";

    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (!POSTGRESQL_PRODUCT.equalsIgnoreCase(product)) {
                log.info("[schema-check] 跳过完整性检查（非 PostgreSQL: {}）", product);
                return;
            }
            Map<String, String> expected = resolveExpectedTables();
            if (expected.isEmpty()) {
                log.warn("[schema-check] 未发现任何带 @Table 名的实体，跳过检查");
                return;
            }
            Map<String, PkState> actual = queryPkStates(conn, expected.keySet());
            List<String> violations = diff(expected, actual);
            if (!violations.isEmpty()) {
                throw new IllegalStateException(buildFailureMessage(violations));
            }
            log.info("[schema-check] Schema 完整性检查通过：{} 张实体表均具备 IDENTITY 主键", expected.size());
        }
    }

    /**
     * 从 JPA 元模型枚举「表名 → 主键列名」期望映射。
     * 实体未声明显式 @Table 名时无法定位物理表：记为告警并跳过该实体
     * （命名规范要求所有实体显式声明 qwt_ 前缀表名，违反本身另行治理）。
     */
    private Map<String, String> resolveExpectedTables() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            Table table = entity.getJavaType().getAnnotation(Table.class);
            String pkAttribute = entity.getSingularAttributes().stream()
                    .filter(SingularAttribute::isId)
                    .map(SingularAttribute::getName)
                    .findFirst()
                    .orElse(null);
            if (table == null || table.name().isBlank() || pkAttribute == null) {
                log.warn("[schema-check] 实体 {} 缺少显式 @Table 名或主键属性，未纳入完整性检查", entity.getName());
                continue;
            }
            expected.put(table.name(), camelToSnake(pkAttribute));
        }
        return expected;
    }

    /**
     * 单次目录查询取回所有目标表的主键状态（跨洲往返昂贵，禁止逐表查询）。
     * 无主键的表仍会出现在结果中（pk_col 为 null），缺表才真正缺席。
     */
    private Map<String, PkState> queryPkStates(Connection conn, java.util.Set<String> tables) throws Exception {
        StringJoiner placeholders = new StringJoiner(", ");
        for (int i = 0; i < tables.size(); i++) {
            placeholders.add("?");
        }
        String sql = """
                SELECT c.relname AS tbl,
                       a.attname AS pk_col,
                       a.attnotnull AS not_null,
                       CASE WHEN a.attname IS NULL THEN false
                            ELSE a.attidentity <> ''
                              OR COALESCE(pg_get_expr(d.adbin, d.adrelid) LIKE 'nextval%%', false)
                       END AS has_identity
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                LEFT JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary
                LEFT JOIN pg_attribute a ON a.attrelid = c.oid
                     AND a.attnum = i.indkey[0] AND a.attnum > 0 AND NOT a.attisdropped
                LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                WHERE c.relkind = 'r' AND c.relname IN (%s)
                """.formatted(placeholders);
        Map<String, PkState> states = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String table : tables) {
                ps.setString(idx++, table);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    states.put(rs.getString("tbl"), new PkState(
                            rs.getString("pk_col"),
                            rs.getBoolean("not_null"),
                            rs.getBoolean("has_identity")));
                }
            }
        }
        return states;
    }

    /** 对比期望与实际，逐表生成违规定性（缺表 / 无主键 / 主键列错位 / 可空主键 / 非 IDENTITY）。 */
    private List<String> diff(Map<String, String> expected, Map<String, PkState> actual) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String table = entry.getKey();
            String pkCol = entry.getValue();
            PkState state = actual.get(table);
            if (state == null) {
                violations.add(table + ": 表不存在（实体已映射但库中缺失）");
            } else if (state.pkCol() == null) {
                violations.add(table + ": 缺少主键");
            } else if (!pkCol.equals(state.pkCol())) {
                violations.add(table + ": 主键列为 " + state.pkCol() + "，实体期望 " + pkCol);
            } else if (!state.notNull()) {
                violations.add(table + ": 主键列 " + pkCol + " 可空");
            } else if (!state.hasIdentity()) {
                violations.add(table + ": 主键列 " + pkCol + " 不是 IDENTITY/序列列（无法生成主键）");
            }
        }
        return violations;
    }

    private String buildFailureMessage(List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Schema 完整性检查失败（拒绝启动） ==========\n");
        for (String v : violations) {
            sb.append("  - ").append(v).append('\n');
        }
        sb.append("""
                影响：主键生成机制损坏，一切新增写入都会失败或产生 id=NULL 脏数据。
                常见根因：数据库迁移/重建时丢失 IDENTITY、序列、主键（如 GUI 工具拖拽复制表）。
                修复：执行 src/main/resources/db/repair-schema-identity.sql（幂等），
                     或以 pg_dump -Fc / pg_restore 从健康备份重建。
                详见 AGENTS.md「Schema 演进与数据库完整性」。
                ==============================================""");
        return sb.toString();
    }

    /** 属性名驼峰 → 物理列名下划线（与 Spring 默认 PhysicalNamingStrategy 一致） */
    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 表的主键状态快照（pk_col 为 null 表示无主键） */
    record PkState(String pkCol, boolean notNull, boolean hasIdentity) {
    }
}
