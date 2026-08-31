package org.quwuting.quwutingservice.resourceaccess.repository;

import org.quwuting.quwutingservice.resourceaccess.entity.ResourceGrantAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceGrantAuditRepository extends JpaRepository<ResourceGrantAudit, Long> {

    List<ResourceGrantAudit> findByGrantIdOrderByCreatedAtDescIdDesc(Long grantId);
}