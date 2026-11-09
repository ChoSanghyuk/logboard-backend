package com.logboard.errorlog;

import com.logboard.common.ApiException;
import com.logboard.common.LogboardProperties;
import com.logboard.crop.Candidate;
import com.logboard.crop.Crop;
import com.logboard.crop.CropEngine;
import com.logboard.crop.FileSegment;
import com.logboard.crop.LineKind;
import com.logboard.crop.LogEntryParser;
import com.logboard.errorlog.ErrorLogDetail.CandidateDto;
import com.logboard.errorlog.ErrorLogDetail.CropDto;
import com.logboard.errorlog.ErrorLogDetail.CropLineDto;
import com.logboard.errorlog.ErrorLogStatusMachine.Action;
import com.logboard.fetch.FetchedLog;
import com.logboard.llm.AnalysisDto;
import com.logboard.llm.AnalysisRepository;
import com.logboard.fetch.SftpLogFetcher;
import com.logboard.server.LogPatternPreset;
import com.logboard.server.Server;
import com.logboard.server.ServerService;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Search (fetch → crop → persist), re-crop, confirm/discard, get and list (design §6). */
@Service
public class ErrorLogService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_PAGE_SIZE = 100;

    private final ErrorLogRepository repo;
    private final AnalysisRepository analyses;
    private final ServerService servers;
    private final SftpLogFetcher fetcher;
    private final ErrorLogIdGenerator ids;
    private final TransactionTemplate tx;
    private final Path root;
    private final CropEngine engine;
    private final int expandStep;
    private final int maxLines;

    public ErrorLogService(ErrorLogRepository repo, AnalysisRepository analyses, ServerService servers, SftpLogFetcher fetcher,
            ErrorLogIdGenerator ids, TransactionTemplate tx, LogboardProperties props) {
        this.repo = repo;
        this.analyses = analyses;
        this.servers = servers;
        this.fetcher = fetcher;
        this.ids = ids;
        this.tx = tx;
        this.root = Path.of(props.storage().root());
        this.engine = new CropEngine(props.crop().before(), props.crop().after(), props.crop().maxLines());
        this.expandStep = props.crop().expandStep();
        this.maxLines = props.crop().maxLines();
    }

    /** Not transactional: the SFTP fetch must not hold a DB transaction. */
    public ErrorLogDetail search(SearchRequest req) {
        Server server = servers.get(req.serverId());
        OffsetDateTime from = req.errorTime().minusMinutes(req.windowMin());
        OffsetDateTime to = req.errorTime().plusMinutes(req.windowMin());
        FetchedLog fetched = fetcher.fetch(server, from, to);
        Path raw = fetched.rawFile();
        try {
            List<FileSegment> segments = fetched.sources().stream().filter(s -> s.lineCount() > 0)
                    .map(s -> new FileSegment(s.firstLine(), s.date())).toList();
            CropEngine.Scan scan = engine.scan(raw, parser(server), segments, from, to);
            Candidate anchor = scan.closestTo(req.errorTime()).orElse(null);
            if (anchor == null) {
                Files.deleteIfExists(raw);
                Files.deleteIfExists(FetchedLog.metaPath(raw));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("errorTime", req.errorTime());
                details.put("windowMin", req.windowMin());
                details.put("from", from);
                details.put("to", to);
                throw new ApiException(HttpStatus.NOT_FOUND, "NO_EXCEPTION_FOUND",
                        "No exception found between " + from + " and " + to, details);
            }
            Crop crop = engine.crop(raw, scan, anchor);
            String rawPath = root.toAbsolutePath().relativize(raw.toAbsolutePath()).toString();
            return tx.execute(status -> {
                ErrorLog log = new ErrorLog(ids.next(), server, req.errorTime(), req.windowMin(), rawPath,
                        scan.totalLines(), toJson(scan.candidates()));
                setCrop(log, crop, anchor);
                repo.saveAndFlush(log);
                writeCropFile(log.getId(), crop);
                return detail(log, crop.lines());
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public ErrorLogDetail get(String id) {
        return detail(repo.findWithServerById(id).orElseThrow(() -> notFound(id)));
    }

    @Transactional(readOnly = true)
    public ErrorLogPage list(List<ErrorStatus> statuses, Long serverId, OffsetDateTime from, OffsetDateTime to,
            int page, int size) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must be >= 0");
        if (size < 1 || size > MAX_PAGE_SIZE) errors.put("size", "must be between 1 and " + MAX_PAGE_SIZE);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                    Map.of("fields", errors));
        }
        Specification<ErrorLog> spec = (r, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) p.add(r.get("status").in(statuses));
            if (serverId != null) p.add(cb.equal(r.get("server").get("id"), serverId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(r.get("requestedTime"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(r.get("requestedTime"), to));
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<ErrorLog> result = repo.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new ErrorLogPage(result.map(ErrorLogSummary::of).getContent(), result.getTotalElements(), page, size);
    }

    /** Re-crop from the stored raw copy (no re-download). Only while CROPPED. */
    @Transactional
    public ErrorLogDetail recrop(String id, CropRequest req) {
        ErrorLog log = repo.findForUpdate(id).orElseThrow(() -> notFound(id));
        ErrorLogStatusMachine.apply(log, Action.RECROP);
        if (req.anchorLine() == null && req.expandBefore() == null && req.expandAfter() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                    Map.of("fields", Map.of("anchorLine", "Give expandBefore, expandAfter or anchorLine")));
        }
        List<Candidate> candidates = fromJson(log.getCandidates());
        CropEngine.Scan scan = new CropEngine.Scan(log.getTotalLines(), candidates);
        Path raw = root.resolve(log.getRawFilePath());
        try {
            Candidate anchor;
            int start = log.getCropStartLine();
            int end = log.getCropEndLine();
            if (req.anchorLine() != null) {
                anchor = scan.at(req.anchorLine()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED", "Request validation failed", Map.of("fields", Map.of("anchorLine",
                                "Not a candidate. Candidates: " + candidates.stream().map(Candidate::anchorLine).toList()))));
                Crop switched = engine.crop(raw, scan, anchor);
                start = switched.startLine();
                end = switched.endLine();
            } else {
                anchor = scan.at(log.getAnchorLine()).orElseThrow();
            }
            Crop crop = engine.expand(raw, scan, anchor, start, end,
                    req.expandBefore() == null ? 0 : req.expandBefore(),
                    req.expandAfter() == null ? 0 : req.expandAfter());
            setCrop(log, crop, anchor);
            writeCropFile(log.getId(), crop);
            return detail(log, crop.lines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional
    public ErrorLogDetail confirm(String id) {
        return transition(id, Action.CONFIRM);
    }

    @Transactional
    public ErrorLogDetail discard(String id) {
        return transition(id, Action.DISCARD);
    }

    /**
     * TERMINATED with {@code resolution}. USER: from CONFIRMED/ANALYZED/DEV_REQUIRED; AGENT (MCP): only from
     * DEV_REQUIRED. Blank resolution → 400 VALIDATION_FAILED on {@code resolution}.
     */
    @Transactional
    public ErrorLogDetail terminate(String id, String resolution, ResolvedBy by) {
        String text = required("resolution", resolution);
        ErrorLog log = repo.findForUpdate(id).orElseThrow(() -> notFound(id));
        ErrorLogStatusMachine.apply(log, by == ResolvedBy.AGENT ? Action.TERMINATE_BY_AGENT : Action.TERMINATE_BY_USER);
        log.setResolution(text);
        log.setResolvedBy(by);
        log.setResolvedAt(OffsetDateTime.now());
        repo.saveAndFlush(log);
        return detail(log);
    }

    /** ANALYZED → DEV_REQUIRED with {@code note}. Blank note → 400 VALIDATION_FAILED on {@code note}. */
    @Transactional
    public ErrorLogDetail devRequired(String id, String note) {
        String text = required("note", note);
        ErrorLog log = repo.findForUpdate(id).orElseThrow(() -> notFound(id));
        ErrorLogStatusMachine.apply(log, Action.DEV_REQUIRED);
        log.setNote(text);
        repo.saveAndFlush(log);
        return detail(log);
    }

    /** Also checked here (not only by bean validation) because MCP calls the service directly. */
    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                    Map.of("fields", Map.of(field, "must not be blank")));
        }
        return value.strip();
    }

    private ErrorLogDetail transition(String id, Action action) {
        ErrorLog log = repo.findForUpdate(id).orElseThrow(() -> notFound(id));
        ErrorLogStatusMachine.apply(log, action);
        repo.saveAndFlush(log);
        return detail(log);
    }

    /** {@code {root}/errors/{ID}.log}: the cropped lines. */
    Path cropFile(String id) {
        return root.resolve("errors").resolve(id + ".log");
    }

    /** Content of the crop file (what the LLM gets as {{errorLog}}). */
    public String cropText(String id) {
        try {
            return Files.readString(cropFile(id), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeCropFile(String id, Crop crop) {
        try {
            Files.createDirectories(cropFile(id).getParent());
            Files.writeString(cropFile(id), crop.text(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setCrop(ErrorLog log, Crop crop, Candidate anchor) {
        log.setCrop(crop.startLine(), crop.endLine(), anchor.anchorLine(), anchor.endLine(), anchor.exceptionClass(),
                anchor.message());
    }

    /** Detail with the crop lines read from the crop file. */
    private ErrorLogDetail detail(ErrorLog log) {
        try {
            List<String> texts = Files.readAllLines(cropFile(log.getId()), StandardCharsets.UTF_8);
            List<Crop.Line> lines = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                int no = log.getCropStartLine() + i;
                lines.add(new Crop.Line(no, texts.get(i), LineKind.of(no, log.getAnchorLine(), log.getAnchorEndLine())));
            }
            return detail(log, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ErrorLogDetail detail(ErrorLog e, List<Crop.Line> lines) {
        CropDto crop = new CropDto(e.getCropStartLine(), e.getCropEndLine(), e.getAnchorLine(), e.getTotalLines(),
                expandStep, maxLines, lines.stream().map(l -> new CropLineDto(l.no(), l.text(), l.kind())).toList());
        List<CandidateDto> candidates = fromJson(e.getCandidates()).stream()
                .map(c -> new CandidateDto(c.anchorLine(), c.time(), c.exceptionClass(), c.message()))
                .toList();
        return new ErrorLogDetail(e.getId(), e.getServer().getId(), e.getServer().getName(), e.getServer().getTimezone(),
                e.getRequestedTime(), e.getExceptionClass(), e.getExceptionMessage(), e.getStatus(),
                e.getCreatedAt(), e.getWindowMin(), e.getNote(), e.getResolution(), e.getResolvedBy(),
                e.getResolvedAt(),
                crop, candidates,
                analyses.findFirstByErrorLogIdOrderByCreatedAtDescIdDesc(e.getId()).map(AnalysisDto::of).orElse(null));
    }

    private static LogEntryParser parser(Server s) {
        Pattern header = s.getLogPatternKey() != null
                ? LogPatternPreset.valueOf(s.getLogPatternKey()).pattern()
                : Pattern.compile(s.getCustomLogRegex());
        return new LogEntryParser(header, ZoneId.of(s.getTimezone()));
    }

    private static String toJson(List<Candidate> candidates) {
        return JSON.writeValueAsString(candidates);
    }

    private static List<Candidate> fromJson(String json) {
        return Arrays.asList(JSON.readValue(json, Candidate[].class));
    }

    private static ApiException notFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Error log " + id + " not found");
    }
}
