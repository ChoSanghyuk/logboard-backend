package com.logboard.server;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByDeletedAtIsNullOrderByNameAsc();

    Optional<Server> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByNameAndDeletedAtIsNull(String name);

    boolean existsByNameAndDeletedAtIsNullAndIdNot(String name, Long id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM error_logs WHERE server_id = :id)", nativeQuery = true)
    boolean isReferencedByErrorLogs(Long id);
}
