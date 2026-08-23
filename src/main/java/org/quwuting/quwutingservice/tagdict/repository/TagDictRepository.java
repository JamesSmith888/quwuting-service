package org.quwuting.quwutingservice.tagdict.repository;

import org.quwuting.quwutingservice.tagdict.entity.TagDict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagDictRepository extends JpaRepository<TagDict, Long> {

    /** 有效字典（active + 未删，按 sortOrder,id 升序）——编辑页表单/选择器数据源 */
    List<TagDict> findByScopeAndActiveTrueAndDeletedFalseOrderBySortOrderAscIdAsc(String scope);

    /** 同 scope + text 查重（管理员新增标签防重复；DB 侧部分唯一索引 uk_qwt_tag_dict_scope_text 兜底） */
    Optional<TagDict> findByScopeAndTextAndDeletedFalse(String scope, String text);

    /**
     * 按 id 批量解析（含停用/任意排序——历史关联不因字典变动而消失；
     * 一次 IN 查询覆盖整页舞伴，规避 N+1）。消费方按自身 id 顺序取。
     */
    @Query("SELECT t FROM TagDict t WHERE t.id IN :ids AND t.deleted = false")
    List<TagDict> findByIds(@Param("ids") Collection<Long> ids);
}
