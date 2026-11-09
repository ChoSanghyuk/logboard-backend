package com.logboard.llm;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    List<PromptTemplate> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /** Run before setting a new default (partial unique index allows one). */
    @Modifying
    @Query("UPDATE PromptTemplate p SET p.isDefault = false WHERE p.isDefault = true AND (:keepId IS NULL OR p.id <> :keepId)")
    void clearDefaultExcept(Long keepId);
}
