package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DancerVenue;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 派生查询 relation 参数类型 = 实体字段类型（{@link DancerVenueRelation} 枚举）——
 * 2026-08-10 修复：曾声明为 String，Spring Data 按字段类型校验绑定参数时抛
 * "argument [HOME] is not assignable to DancerVenueRelation"（createDancer 因前端
 * 不传 homeVenueId 从未触发该缺陷，updateDancer HOME 替换首次真实暴露）。
 */
public interface DancerVenueRepository extends JpaRepository<DancerVenue, Long> {

    Optional<DancerVenue> findByDancerIdAndVenueIdAndRelationAndDeletedFalse(
            Long dancerId, Long venueId, DancerVenueRelation relation);

    /** 舞伴某关系类型全部记录（编辑时 HOME 关系完整替换用） */
    List<DancerVenue> findByDancerIdAndRelationAndDeletedFalse(Long dancerId, DancerVenueRelation relation);

    /**
     * 批量舞伴的场所关系简述（列表页/详情页一次 JOIN 查询覆盖整页舞伴，规避 N+1）。
     * 返回 Object[]{dancerId, venueId, venueName, venueCity, venueDistrict, relation, note}，
     * 按舞伴 id 与关系创建时间升序（HOME 最早一条可作"常去"首选）。
     * 场所软删（v.deleted = true）时该关联一并排除。
     */
    @Query(value = """
            SELECT dv.dancer_id, v.id AS venue_id, v.name, v.city, v.district, dv.relation, dv.note
            FROM qwt_dancer_venues dv
            JOIN qwt_venues v ON v.id = dv.venue_id
            WHERE dv.dancer_id IN :dancerIds AND dv.deleted = false AND v.deleted = false
            ORDER BY dv.dancer_id, dv.created_at ASC
            """, nativeQuery = true)
    List<Object[]> findVenueBriefsByDancerIds(@Param("dancerIds") List<Long> dancerIds);
}
