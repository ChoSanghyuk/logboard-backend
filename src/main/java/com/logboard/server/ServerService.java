package com.logboard.server;

import com.logboard.common.ApiException;
import com.logboard.common.SecretCipher;
import com.logboard.server.ConnectionTestResult.InstanceTestResult;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServerService {

    private final ServerRepository repo;
    private final ServerInstanceRepository instanceRepo;
    private final SecretCipher cipher;
    private final SshConnector ssh;

    public ServerService(ServerRepository repo, ServerInstanceRepository instanceRepo, SecretCipher cipher,
            SshConnector ssh) {
        this.repo = repo;
        this.instanceRepo = instanceRepo;
        this.cipher = cipher;
        this.ssh = ssh;
    }

    @Transactional(readOnly = true)
    public List<ServerDto> list() {
        List<Server> list = repo.findByDeletedAtIsNullOrderByNameAsc();
        Map<Long, List<ServerInstance>> byServer = instanceRepo.findByServerIdInAndDeletedAtIsNullOrderByPositionAsc(
                list.stream().map(Server::getId).toList()).stream()
                .collect(Collectors.groupingBy(ServerInstance::getServerId));
        return list.stream().map(s -> ServerDto.of(s, byServer.getOrDefault(s.getId(), List.of()))).toList();
    }

    @Transactional
    public ServerDto create(ServerUpsert req) {
        Map<String, Object> errors = validate(req, Map.of());
        throwIfAny(errors);
        if (repo.existsByNameAndDeletedAtIsNull(req.name())) {
            throw duplicateName(req.name());
        }
        Server s = new Server();
        apply(req, s);
        repo.saveAndFlush(s);
        return applyInstances(req, s, Map.of());
    }

    @Transactional
    public ServerDto update(long id, ServerUpsert req) {
        Server s = get(id);
        Map<Long, ServerInstance> existing = instanceRepo.findByServerIdAndDeletedAtIsNullOrderByPositionAsc(id).stream()
                .collect(Collectors.toMap(ServerInstance::getId, i -> i));
        Map<String, Object> errors = validate(req, existing);
        throwIfAny(errors);
        if (repo.existsByNameAndDeletedAtIsNullAndIdNot(req.name(), id)) {
            throw duplicateName(req.name());
        }
        apply(req, s);
        repo.saveAndFlush(s);
        return applyInstances(req, s, existing);
    }

    /** Forgets the instance's trusted host key; its next successful login pins the key presented then. */
    @Transactional
    public ServerDto resetHostKey(long id, long instanceId) {
        Server s = get(id);
        ServerInstance i = instanceRepo.findByIdAndServerIdAndDeletedAtIsNull(instanceId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Instance " + instanceId + " of server " + id + " not found"));
        i.setHostKeyFingerprint(null);
        instanceRepo.saveAndFlush(i);
        return ServerDto.of(s, activeInstances(id));
    }

    /** Hard-deletes (instances cascade), or soft-deletes server and instances when error logs reference the server. */
    @Transactional
    public void delete(long id) {
        Server s = get(id);
        if (repo.isReferencedByErrorLogs(id)) {
            OffsetDateTime now = OffsetDateTime.now();
            s.setDeletedAt(now);
            activeInstances(id).forEach(i -> i.setDeletedAt(now));
        } else {
            repo.delete(s);
        }
    }

    /** All active instances in parallel. Not transactional: no DB transaction is held during SSH. */
    public ConnectionTestResult test(long id) {
        get(id);
        List<InstanceTestResult> results = SshConnector.inParallel(activeInstances(id), ssh::test);
        return new ConnectionTestResult(results.stream().allMatch(InstanceTestResult::ok), results);
    }

    /** Active (not soft-deleted) server, or 404. */
    @Transactional(readOnly = true)
    public Server get(long id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Server " + id + " not found"));
    }

    /** Active instances of the server, by position. */
    @Transactional(readOnly = true)
    public List<ServerInstance> activeInstances(long serverId) {
        return instanceRepo.findByServerIdAndDeletedAtIsNullOrderByPositionAsc(serverId);
    }

    private static void apply(ServerUpsert req, Server s) {
        s.setName(req.name());
        s.setRotatedPattern(req.rotatedPattern());
        s.setLogPatternKey(req.logPatternKey());
        s.setCustomLogRegex(req.customLogRegex());
        s.setTimezone(req.timezone());
        s.setCharset(req.charset());
    }

    /** Creates/updates the listed instances (position = list index) and removes the existing ones not listed. */
    private ServerDto applyInstances(ServerUpsert req, Server s, Map<Long, ServerInstance> existing) {
        List<ServerInstance> result = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        for (int n = 0; n < req.instances().size(); n++) {
            InstanceUpsert in = req.instances().get(n);
            ServerInstance i = in.id() != null ? existing.get(in.id()) : new ServerInstance();
            if (in.id() != null) {
                kept.add(in.id());
                if (!i.getHost().equals(in.host()) || i.getSshPort() != in.sshPort()) {
                    i.setHostKeyFingerprint(null); // another machine: trust its key on the next login
                }
            }
            i.setServerId(s.getId());
            i.setPosition(n);
            i.setHost(in.host());
            i.setSshPort(in.sshPort());
            i.setLogPath(in.logPath());
            i.setUsername(in.username());
            i.setAuthType(in.authType());
            if (in.secret() != null) {
                i.setSecretEnc(cipher.encrypt(in.secret()));
            }
            result.add(instanceRepo.save(i));
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (ServerInstance old : existing.values()) {
            if (kept.contains(old.getId())) {
                continue;
            }
            if (instanceRepo.isReferencedByErrorLogs(old.getId())) {
                old.setDeletedAt(now);
            } else {
                instanceRepo.delete(old);
            }
        }
        instanceRepo.flush();
        return ServerDto.of(s, result);
    }

    /** Checks bean validation can't express. Returns field → message. */
    private static Map<String, Object> validate(ServerUpsert req, Map<Long, ServerInstance> existing) {
        Map<String, Object> errors = new LinkedHashMap<>();
        validateInstances(req.instances(), existing, errors);
        if ((req.logPatternKey() == null) == (req.customLogRegex() == null)) {
            errors.put("logPatternKey", "Set exactly one of logPatternKey or customLogRegex");
        } else if (req.logPatternKey() != null) {
            if (Arrays.stream(LogPatternPreset.values()).noneMatch(p -> p.name().equals(req.logPatternKey()))) {
                errors.put("logPatternKey", "Unknown log pattern '" + req.logPatternKey()
                        + "'. Allowed: " + Arrays.toString(LogPatternPreset.values()));
            }
        } else {
            String problem = checkCustomRegex(req.customLogRegex());
            if (problem != null) {
                errors.put("customLogRegex", problem);
            }
        }
        if (req.rotatedPattern() != null && req.instances() != null) {
            try {
                for (InstanceUpsert in : req.instances()) {
                    RotatedPattern.parse(req.rotatedPattern(), in.logPath());
                }
            } catch (IllegalArgumentException e) {
                errors.put("rotatedPattern", e.getMessage());
            }
        }
        try {
            ZoneId.of(req.timezone());
        } catch (DateTimeException e) {
            errors.put("timezone", "Unknown timezone '" + req.timezone() + "' (use an IANA ID like Asia/Seoul)");
        }
        try {
            Charset.forName(req.charset());
        } catch (IllegalArgumentException e) {
            errors.put("charset", "Unsupported charset '" + req.charset() + "'");
        }
        return errors;
    }

    /**
     * Per instance (keys {@code instances[N].field}): an {@code id} must be an active instance of this server (once);
     * (host, sshPort, logPath) must be unique (reported on the later one); the secret is required for a new instance,
     * when authType changes and when none is stored.
     */
    private static void validateInstances(List<InstanceUpsert> instances, Map<Long, ServerInstance> existing,
            Map<String, Object> errors) {
        if (instances == null || instances.isEmpty() || instances.size() > ServerUpsert.MAX_INSTANCES) {
            errors.put("instances", "Give 1 to " + ServerUpsert.MAX_INSTANCES + " instances");
            return;
        }
        Set<Long> seenIds = new HashSet<>();
        Set<List<Object>> seenTargets = new HashSet<>();
        for (int n = 0; n < instances.size(); n++) {
            InstanceUpsert in = instances.get(n);
            String key = "instances[" + n + "].";
            ServerInstance old = null;
            if (in.id() != null) {
                old = existing.get(in.id());
                if (old == null || !seenIds.add(in.id())) {
                    errors.put(key + "id", old == null ? "Not an instance of this server: " + in.id()
                            : "Instance " + in.id() + " is listed twice");
                    continue;
                }
            }
            if (!seenTargets.add(Arrays.asList(in.host(), in.sshPort(), in.logPath()))) {
                errors.put(key + "host", "Same host, sshPort and logPath as another instance");
            }
            if (in.secret() == null) {
                if (old == null) {
                    errors.put(key + in.secretField(), "Required for a new instance with authType " + in.authType());
                } else if (old.getAuthType() != in.authType() || old.getSecretEnc() == null) {
                    errors.put(key + in.secretField(),
                            "Required: authType changed to " + in.authType() + " or no secret is stored");
                }
            }
        }
    }

    /** @return null if valid, else a message for the user */
    static String checkCustomRegex(String regex) {
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "Regex does not compile: " + e.getDescription() + " near index " + e.getIndex();
        }
        List<String> missing = List.of("ts", "level").stream()
                .filter(g -> !p.namedGroups().containsKey(g)).toList();
        if (!missing.isEmpty()) {
            return "Regex must contain named groups (?<ts>...) and (?<level>...). Missing: " + String.join(", ", missing);
        }
        return null;
    }

    private static void throwIfAny(Map<String, Object> fieldErrors) {
        if (!fieldErrors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                    Map.of("fields", fieldErrors));
        }
    }

    private static ApiException duplicateName(String name) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_NAME", "A server named '" + name + "' already exists",
                Map.of("field", "name"));
    }
}
