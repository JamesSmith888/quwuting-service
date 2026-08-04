package org.quwuting.quwutingservice.config;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.venue.entity.Venue;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SchemaIntegrityChecker 单元测试（Mockito，不依赖真实数据库）。
 * 覆盖：非 PostgreSQL 跳过、健康库通过、缺表/无主键/非 IDENTITY 各自拒绝启动。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaIntegrityCheckerTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private EntityManagerFactory entityManagerFactory;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;

    private SchemaIntegrityChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        checker = new SchemaIntegrityChecker(dataSource, entityManagerFactory);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);

        // 元模型：两个实体（@Table 名 + id 主键属性）。
        // 注意：mock 构造必须先于 when(...).thenReturn(...) 完成，
        // 嵌套在 thenReturn 实参里构造会触发 UnfinishedStubbing
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<?> venueEntity = entity(Venue.class);
        EntityType<?> userEntity = entity(User.class);
        when(entityManagerFactory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(venueEntity, userEntity));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityType<?> entity(Class<?> javaType) {
        EntityType et = mock(EntityType.class);
        when(et.getJavaType()).thenReturn(javaType);
        when(et.getName()).thenReturn(javaType.getSimpleName());
        SingularAttribute idAttr = mock(SingularAttribute.class);
        when(idAttr.isId()).thenReturn(true);
        when(idAttr.getName()).thenReturn("id");
        when(et.getSingularAttributes()).thenReturn(Set.of(idAttr));
        return et;
    }

    /** 按行构造目录查询结果（tbl / pk_col / not_null / has_identity） */
    private void mockRows(Object[][] rows) throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        final int[] cursor = {-1};
        when(resultSet.next()).thenAnswer(inv -> cursor[0]++ < rows.length - 1);
        when(resultSet.getString("tbl")).thenAnswer(inv -> rows[Math.max(cursor[0], 0)][0]);
        when(resultSet.getString("pk_col")).thenAnswer(inv -> rows[Math.max(cursor[0], 0)][1]);
        when(resultSet.getBoolean("not_null")).thenAnswer(inv -> (Boolean) rows[Math.max(cursor[0], 0)][2]);
        when(resultSet.getBoolean("has_identity")).thenAnswer(inv -> (Boolean) rows[Math.max(cursor[0], 0)][3]);
    }

    @Test
    void skipsNonPostgresql() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("H2");
        assertDoesNotThrow(() -> checker.run(null));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void passesWhenAllTablesHealthy() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        mockRows(new Object[][]{
                {"qwt_users", "id", true, true},
                {"qwt_venues", "id", true, true},
        });
        assertDoesNotThrow(() -> checker.run(null));
    }

    @Test
    void rejectsMissingTable() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        mockRows(new Object[][]{
                {"qwt_users", "id", true, true},
                // qwt_venues 缺席
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> checker.run(null));
        assertTrue(ex.getMessage().contains("qwt_venues"));
        assertTrue(ex.getMessage().contains("表不存在"));
    }

    @Test
    void rejectsMissingPrimaryKey() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        mockRows(new Object[][]{
                {"qwt_users", "id", true, true},
                {"qwt_venues", null, false, false},
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> checker.run(null));
        assertTrue(ex.getMessage().contains("qwt_venues"));
        assertTrue(ex.getMessage().contains("缺少主键"));
    }

    @Test
    void rejectsNonIdentityPrimaryKey() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        mockRows(new Object[][]{
                {"qwt_users", "id", true, true},
                {"qwt_venues", "id", true, false},
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> checker.run(null));
        assertTrue(ex.getMessage().contains("qwt_venues"));
        assertTrue(ex.getMessage().contains("IDENTITY"));
    }
}
