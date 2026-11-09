package com.logboard.llm;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LlmEndpointRepository extends JpaRepository<LlmEndpoint, Long> {

    List<LlmEndpoint> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /** Run before setting a new default (partial unique index allows one). */
    @Modifying
    @Query("UPDATE LlmEndpoint e SET e.isDefault = false WHERE e.isDefault = true AND (:keepId IS NULL OR e.id <> :keepId)")
    void clearDefaultExcept(Long keepId);
}
