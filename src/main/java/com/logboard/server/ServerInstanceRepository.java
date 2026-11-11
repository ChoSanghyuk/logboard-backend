package com.logboard.server;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ServerInstanceRepository extends JpaRepository<ServerInstance, Long> {

    List<ServerInstance> findByServerIdAndDeletedAtIsNullOrderByPositionAsc(long serverId);

    List<ServerInstance> findByServerIdInAndDeletedAtIsNullOrderByPositionAsc(Collection<Long> serverIds);

    Optional<ServerInstance> findByIdAndServerIdAndDeletedAtIsNull(long id, long serverId);

    /** Trust on first use: stores the fingerprint only if none is stored yet. */
    @Modifying
    @Transactional
    @Query("UPDATE ServerInstance i SET i.hostKeyFingerprint = :fingerprint WHERE i.id = :id AND i.hostKeyFingerprint IS NULL")
    int pinHostKey(Long id, String fingerprint);

    /** Referenced = holds an error log's crop, or one of its raw copies (a candidate may switch to it). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM error_logs WHERE instance_id = :id OR jsonb_exists(raw_files::jsonb, CAST(:id AS TEXT)))",
            nativeQuery = true)
    boolean isReferencedByErrorLogs(long id);
}
