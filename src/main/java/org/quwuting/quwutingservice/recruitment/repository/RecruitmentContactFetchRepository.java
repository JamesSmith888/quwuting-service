package org.quwuting.quwutingservice.recruitment.repository;

import org.quwuting.quwutingservice.recruitment.entity.RecruitmentContactFetch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 联系方式获取留痕仓储。
 * <p>
 * 写入唯一通道 = 原生 upsert（ON CONFLICT 列清单推断，禁 ON CONSTRAINT——
 * 对唯一索引/约束两种形态均健壮，见 docs/agents/13-code-standards.md）。
 */
public interface RecruitmentContactFetchRepository extends JpaRepository<RecruitmentContactFetch, Long> {

    /**
     * 幂等一记：同用户同招工重复获取不重复计数。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_recruitment_contacts (created_at, updated_at, deleted, recruitment_id, user_id) "
            + "VALUES (now(), now(), false, :recruitmentId, :userId) "
            + "ON CONFLICT (recruitment_id, user_id) DO NOTHING", nativeQuery = true)
    void insertFetchIfAbsent(@Param("recruitmentId") Long recruitmentId, @Param("userId") Long userId);
}
