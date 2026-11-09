package com.logboard.server;

import com.logboard.common.ApiException;
import com.logboard.common.SecretCipher;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServerService {

    private final ServerRepository repo;
    private final SecretCipher cipher;
    private final SshConnector ssh;

    public ServerService(ServerRepository repo, SecretCipher cipher, SshConnector ssh) {
        this.repo = repo;
        this.cipher = cipher;
        this.ssh = ssh;
    }

    @Transactional(readOnly = true)
    public List<ServerDto> list() {
        return repo.findByDeletedAtIsNullOrderByNameAsc().stream().map(ServerDto::of).toList();
    }

    @Transactional
    public ServerDto create(ServerUpsert req) {
        Map<String, Object> errors = validate(req);
        if (req.secret() == null) {
            errors.put(req.secretField(), "Required for authType " + req.authType());
        }
        throwIfAny(errors);
        if (repo.existsByNameAndDeletedAtIsNull(req.name())) {
            throw duplicateName(req.name());
        }
        Server s = new Server();
        apply(req, s);
        return ServerDto.of(repo.saveAndFlush(s));
    }

    @Transactional
    public ServerDto update(long id, ServerUpsert req) {
        Server s = get(id);
        Map<String, Object> errors = validate(req);
        if (req.secret() == null && (req.authType() != s.getAuthType() || s.getSecretEnc() == null)) {
            errors.put(req.secretField(), "Required: authType changed to " + req.authType() + " or no secret is stored");
        }
        throwIfAny(errors);
        if (repo.existsByNameAndDeletedAtIsNullAndIdNot(req.name(), id)) {
            throw duplicateName(req.name());
        }
        if (!s.getHost().equals(req.host()) || s.getSshPort() != req.sshPort()) {
            s.setHostKeyFingerprint(null); // another machine: trust its key on the next login
        }
        apply(req, s);
        return ServerDto.of(repo.saveAndFlush(s));
    }

    /** Forgets the trusted host key; the next successful login pins the key the server presents then. */
    @Transactional
    public ServerDto resetHostKey(long id) {
        Server s = get(id);
        s.setHostKeyFingerprint(null);
        return ServerDto.of(repo.saveAndFlush(s));
    }

    /** Hard-deletes, or soft-deletes when error logs reference the server. */
    @Transactional
    public void delete(long id) {
        Server s = get(id);
        if (repo.isReferencedByErrorLogs(id)) {
            s.setDeletedAt(OffsetDateTime.now());
        } else {
            repo.delete(s);
        }
    }

    /** Not transactional: no DB transaction is held during SSH, and the host-key pin is its own update. */
    public ConnectionTestResult test(long id) {
        return ssh.test(get(id));
    }

    /** Active (not soft-deleted) server, or 404. */
    @Transactional(readOnly = true)
    public Server get(long id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Server " + id + " not found"));
    }

    private void apply(ServerUpsert req, Server s) {
        s.setName(req.name());
        s.setHost(req.host());
        s.setSshPort(req.sshPort());
        s.setUsername(req.username());
        s.setAuthType(req.authType());
        if (req.secret() != null) {
            s.setSecretEnc(cipher.encrypt(req.secret()));
        }
        s.setLogPath(req.logPath());
        s.setRotatedPattern(req.rotatedPattern());
        s.setLogPatternKey(req.logPatternKey());
        s.setCustomLogRegex(req.customLogRegex());
        s.setTimezone(req.timezone());
        s.setCharset(req.charset());
    }

    /** Checks bean validation can't express. Returns field → message. */
    private static Map<String, Object> validate(ServerUpsert req) {
        Map<String, Object> errors = new LinkedHashMap<>();
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
        if (req.rotatedPattern() != null) {
            try {
                RotatedPattern.parse(req.rotatedPattern(), req.logPath());
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
