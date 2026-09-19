package org.quwuting.quwutingservice.wxacode.repository;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.wxacode.service.WxacodeSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 小程序码静态资产仓储（2026-09-19，V30 qwt_wxacode_assets）。
 * <p>
 * <b>职责边界</b>：只负责"内容指纹 → 图片字节"的持久化读写，不含任何生成逻辑
 * （生成与"物化一次"的编排在 {@code WxacodeShareService}）。
 * <p>
 * <b>为什么用 JdbcTemplate 而不是 JPA 仓储</b>：
 * <ul>
 *   <li>本表刻意不建 JPA 实体——{@code ddl-auto=validate} 下实体的 BLOB 类型映射
 *       （{@code byte[]} 默认 varbinary(255) / {@code @Lob} 映射 longblob）会与本表的
 *       mediumblob 产生无谓的方言耦合，而这张表没有任何实体级行为需求；</li>
 *   <li>写入是"幂等插入单行"，{@code INSERT ... ON DUPLICATE KEY UPDATE asset_key =
 *       asset_key} 一条语句即完成（对齐 {@code PointsUnlockRepository#insertIfAbsent}
 *       的原生 upsert 范式：主代码零 catch 唯一键冲突——Hibernate 在
 *       DataIntegrityViolationException 后会把事务标记 rollback-only，catch 吞掉
 *       异常仍会在提交时炸成 UnexpectedRollbackException，见 22 号文档）。</li>
 * </ul>
 * <p>
 * 无 deleted 列：资产由系统管理、无用户可见删除语义，重置 = 按指纹 DELETE 一行
 * （理由见 V30 迁移注释）。
 */
@Repository
@RequiredArgsConstructor
public class WxacodeAssetRepository {

    /** 读：单行按主键点查（键唯一，恒 ≤1 行） */
    private static final String SELECT_BYTES_SQL =
            "SELECT image_bytes FROM qwt_wxacode_assets WHERE asset_key = ?";

    /** 写：幂等插入（已存在 = no-op 保留先写入者，同指纹内容一致故无更新必要） */
    private static final String INSERT_IF_ABSENT_SQL =
            "INSERT INTO qwt_wxacode_assets " +
            "(asset_key, app_id, page_path, scene, env_version, content_type, byte_size, image_bytes, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE asset_key = asset_key";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 按内容指纹取已物化的码图字节。
     *
     * @param assetKey {@link WxacodeSpec#fingerprint()}
     * @return 码图字节；该指纹尚未物化 = null（调用方据此触发一次生成）
     */
    @Transactional(readOnly = true)
    public byte[] findImageBytes(String assetKey) {
        List<byte[]> rows = jdbcTemplate.query(
                SELECT_BYTES_SQL,
                (rs, rowNum) -> rs.getBytes("image_bytes"),
                assetKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 物化一份码图（幂等）：同一指纹重复写入 = no-op（保留首次内容，二者字节等价）。
     * <p>
     * 四维冗余列一并落库，使人工排查与"按环境清理"无需反解指纹。
     *
     * @param spec        码规格（指纹与各维度列的唯一来源）
     * @param imageBytes  微信返回的图片原始字节
     * @param contentType 响应内容类型（当前恒 image/jpeg）
     */
    @Transactional
    public void insertIfAbsent(WxacodeSpec spec, byte[] imageBytes, String contentType) {
        jdbcTemplate.update(
                INSERT_IF_ABSENT_SQL,
                spec.fingerprint(),
                spec.appId(),
                spec.page(),
                spec.scene(),
                spec.envVersion(),
                contentType,
                imageBytes.length,
                imageBytes,
                Timestamp.valueOf(LocalDateTime.now()));
    }
}
