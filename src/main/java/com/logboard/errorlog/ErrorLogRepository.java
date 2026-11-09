package com.logboard.errorlog;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ErrorLogRepository extends JpaRepository<ErrorLog, String>, JpaSpecificationExecutor<ErrorLog> {

    @Override
    @EntityGraph(attributePaths = "server")
    Page<ErrorLog> findAll(Specification<ErrorLog> spec, Pageable pageable);

    @EntityGraph(attributePaths = "server")
    Optional<ErrorLog> findWithServerById(String id);

    /** Row-locks the error log for a status or crop change. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ErrorLog e JOIN FETCH e.server WHERE e.id = :id")
    Optional<ErrorLog> findForUpdate(String id);
}
