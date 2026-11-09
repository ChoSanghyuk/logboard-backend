package com.logboard.server;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByDeletedAtIsNullOrderByNameAsc();

    Optional<Server> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByNameAndDeletedAtIsNull(String name);

    boolean existsByNameAndDeletedAtIsNullAndIdNot(String name, Long id);

    /** Trust on first use: stores the fingerprint only if none is stored yet. */
    @Modifying
    @Transactional
    @Query("UPDATE Server s SET s.hostKeyFingerprint = :fingerprint WHERE s.id = :id AND s.hostKeyFingerprint IS NULL")
    int pinHostKey(Long id, String fingerprint);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM error_logs WHERE server_id = :id)", nativeQuery = true)
    boolean isReferencedByErrorLogs(Long id);
}
