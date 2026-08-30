package org.quwuting.quwutingservice.recruitment.repository;

import org.quwuting.quwutingservice.recruitment.entity.RecruitmentContactFetch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 联系方式获取留痕仓储。
 * <p>
 * 写入唯一通道 = 原生 upsert（PG: ON CONFLICT 列清单推断；MySQL 迁移（2026-08-30）：
 * ON DUPLICATE KEY UPDATE id=id——仅唯一键冲突时 no-op，不吞其他错误（比 INSERT IGNORE
 * 精确）。依赖 qwt_recruitment_contacts(recruitment_id, user_id) 唯一索引，见 V61）。
 */
public interface RecruitmentContactFetchRepository extends JpaRepository<RecruitmentContactFetch, Long> {

    /**
     * 幂等一记：同用户同招工重复获取不重复计数。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_recruitment_contacts (created_at, updated_at, deleted, recruitment_id, user_id) "
            + "VALUES (now(), now(), false, :recruitmentId, :userId) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    void insertFetchIfAbsent(@Param("recruitmentId") Long recruitmentId, @Param("userId") Long userId);
}
