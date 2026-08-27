package org.quwuting.quwutingservice.user.repository;

import org.quwuting.quwutingservice.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOpenIdAndDeletedFalse(String openId);

    Optional<User> findByIdAndDeletedFalse(Long id);

    /**
     * 管理端用户分页列表（2026-08-27，docs/agents/23；仅 ADMIN）：keyword 空 = 全部
     * 用户（id 倒序）；非空 = 昵称模糊匹配（忽略大小写，LOWER LIKE）。无昵称用户
     * （nickname null）在关键词过滤时自然不匹配；keyword 为 null/空串时恒返回全部。
     */
    @Query("SELECT u FROM User u WHERE (:keyword IS NULL OR :keyword = '' " +
            "OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))) ORDER BY u.id DESC")
    Page<User> findPageByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
