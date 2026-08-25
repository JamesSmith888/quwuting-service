package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 联系方式需求记录仓库（2026-08-24 风控留痕，qwt_demand_records）。
 * 只写不删（锚点记录，与 PointsUnlock 同模式）；查询维度 = 用户（异常解锁频次）/
 * 舞伴（骚扰投诉排查）——本期落库留痕，风控查询面在管理端按需扩展。
 * <p>
 * 2026-08-26 新增用户视角查询：个人中心「我的需求单」列表（findByUserIdOrderByIdDesc，
 * 走已建 idx_qwt_demand_records_user 索引）+ 详情（findByUserIdAndId，归属校验——
 * 需求单是用户级资源，越权查询后端直接 1001）。
 */
public interface DemandRecordRepository extends JpaRepository<DemandRecord, Long> {

    /** 我的需求单（分页倒序，新记录在前；个人中心「我的需求单」数据源） */
    @Query("SELECT d FROM DemandRecord d WHERE d.userId = :userId ORDER BY d.id DESC")
    Page<DemandRecord> findByUserIdOrderByIdDesc(@Param("userId") Long userId, Pageable pageable);

    /** 我的单条需求单（详情；userId + id 双重条件 = 归属校验，越权查不到） */
    Optional<DemandRecord> findByUserIdAndId(Long userId, Long id);
}
