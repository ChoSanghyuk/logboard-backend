package com.logboard.llm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    @EntityGraph(attributePaths = {"llmEndpoint", "promptTemplate"})
    Optional<Analysis> findWithNamesById(Long id);

    @EntityGraph(attributePaths = {"llmEndpoint", "promptTemplate"})
    List<Analysis> findByErrorLogIdOrderByCreatedAtDescIdDesc(String errorLogId);

    @EntityGraph(attributePaths = {"llmEndpoint", "promptTemplate"})
    Optional<Analysis> findFirstByErrorLogIdOrderByCreatedAtDescIdDesc(String errorLogId);

    /** Newest analysis with the given status (MCP get_error_log uses the latest DONE one). */
    Optional<Analysis> findFirstByErrorLogIdAndStatusOrderByCreatedAtDescIdDesc(String errorLogId, AnalysisStatus status);

    boolean existsByLlmEndpointId(Long llmEndpointId);

    boolean existsByPromptTemplateId(Long promptTemplateId);

    /** Analyses left PENDING by a previous run can never finish. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Analysis a SET a.status = com.logboard.llm.AnalysisStatus.FAILED, a.errorMessage = :message, "
            + "a.finishedAt = CURRENT_TIMESTAMP WHERE a.status = com.logboard.llm.AnalysisStatus.PENDING")
    int failAllPending(String message);
}
