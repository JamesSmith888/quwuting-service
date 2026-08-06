package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerRecognitionTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DancerRecognitionTagRepository extends JpaRepository<DancerRecognitionTag, Long> {

    /** 取消认可时级联删除该认可携带的全部标签（每日一记模型：取消 = 当日贡献整体移除） */
    void deleteByRecognitionId(Long recognitionId);

    /**
     * 按认可记录 ID 批量取标签（我的认可明细用）。
     * 返回 Object[]{recognitionId, tag}。
     */
    @Query("SELECT t.recognitionId, t.tag FROM DancerRecognitionTag t " +
           "WHERE t.recognitionId IN :recognitionIds AND t.deleted = false")
    List<Object[]> findTagsByRecognitionIds(@Param("recognitionIds") List<Long> recognitionIds);

    /**
     * 单舞伴标签聚合（详情页标签云），按计数倒序。
     * 返回 Object[]{tag, count}。
     */
    @Query(value = """
            SELECT t.tag, COUNT(*)
            FROM qwt_dancer_recognition_tags t
            WHERE t.dancer_id = :dancerId AND t.deleted = false
            GROUP BY t.tag
            ORDER BY COUNT(*) DESC, t.tag ASC
            """, nativeQuery = true)
    List<Object[]> aggregateByDancer(@Param("dancerId") Long dancerId);

    /**
     * 批量舞伴标签聚合（列表页 Top 标签，一次 IN 查询覆盖整页舞伴，规避 N+1）。
     * 返回 Object[]{dancerId, tag, count}。
     */
    @Query(value = """
            SELECT t.dancer_id, t.tag, COUNT(*)
            FROM qwt_dancer_recognition_tags t
            WHERE t.dancer_id IN :dancerIds AND t.deleted = false
            GROUP BY t.dancer_id, t.tag
            ORDER BY t.dancer_id, COUNT(*) DESC
            """, nativeQuery = true)
    List<Object[]> aggregateByDancerIds(@Param("dancerIds") List<Long> dancerIds);
}
