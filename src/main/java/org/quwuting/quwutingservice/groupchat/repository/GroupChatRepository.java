package org.quwuting.quwutingservice.groupchat.repository;

import org.quwuting.quwutingservice.groupchat.entity.GroupChat;
import org.quwuting.quwutingservice.groupchat.enums.GroupChatScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 舞友群仓储（V33 新增）。
 * <p>
 * 公开读只查 enabled=true AND deleted=false；管理端查 deleted=false（含已下线）。
 * 排序统一 displayOrder 升序 + id 升序（稳定排序，运营手动排权重）。
 */
public interface GroupChatRepository extends JpaRepository<GroupChat, Long> {

    /** 公开读：启用 + 未软删的全部群（按运营排序） */
    List<GroupChat> findByDeletedFalseAndEnabledTrueOrderByDisplayOrderAscIdAsc();

    /** 管理端：未软删的全部群（含已下线） */
    List<GroupChat> findByDeletedFalseOrderByDisplayOrderAscIdAsc();

    /** 按维度查启用群（公开分组组装用） */
    List<GroupChat> findByDeletedFalseAndEnabledTrueAndScopeOrderByDisplayOrderAscIdAsc(GroupChatScope scope);

    /** 按 id 查未软删群（管理端更新/上下线/删除共用） */
    Optional<GroupChat> findByIdAndDeletedFalse(Long id);
}
