package org.quwuting.quwutingservice.webauth.repository;

import jakarta.persistence.LockModeType;
import org.quwuting.quwutingservice.webauth.entity.WebLoginSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WebLoginSessionRepository extends JpaRepository<WebLoginSession, Long> {

    Optional<WebLoginSession> findBySessionIdAndDeletedFalse(String sessionId);

    /** 轮询/确认取 token 用悲观锁，保证 tokenIssued 一次性下发（单管理员场景下极少争用） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM WebLoginSession s WHERE s.sessionId = :sessionId AND s.deleted = false")
    Optional<WebLoginSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);
}
