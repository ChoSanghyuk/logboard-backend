package com.logboard.llm;

import com.logboard.common.SecretCipher;
import com.logboard.llm.LlmClient.LlmException;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmEndpointService {

    static final String TEST_PROMPT = "Reply with the single word OK.";

    private final LlmEndpointRepository repo;
    private final AnalysisRepository analyses;
    private final SecretCipher cipher;
    private final LlmClient llm;

    public LlmEndpointService(LlmEndpointRepository repo, AnalysisRepository analyses, SecretCipher cipher,
            LlmClient llm) {
        this.repo = repo;
        this.analyses = analyses;
        this.cipher = cipher;
        this.llm = llm;
    }

    @Transactional(readOnly = true)
    public List<LlmEndpointDto> list() {
        return repo.findAllByOrderByNameAsc().stream().map(LlmEndpointDto::of).toList();
    }

    @Transactional
    public LlmEndpointDto create(LlmEndpointUpsert req) {
        String baseUrl = validate(req);
        if (repo.existsByName(req.name())) {
            throw Checks.duplicateName(req.name());
        }
        if (req.isDefault()) {
            repo.clearDefaultExcept(null);
        }
        LlmEndpoint e = new LlmEndpoint();
        apply(req, baseUrl, e);
        return LlmEndpointDto.of(repo.saveAndFlush(e));
    }

    @Transactional
    public LlmEndpointDto update(long id, LlmEndpointUpsert req) {
        LlmEndpoint e = get(id);
        String baseUrl = validate(req);
        if (repo.existsByNameAndIdNot(req.name(), id)) {
            throw Checks.duplicateName(req.name());
        }
        if (req.isDefault()) {
            repo.clearDefaultExcept(id); // before changing e: the bulk update flushes pending changes
        }
        apply(req, baseUrl, e);
        if (req.clearApiKey()) {
            e.setApiKeyEnc(null);
        }
        return LlmEndpointDto.of(repo.saveAndFlush(e));
    }

    @Transactional
    public void delete(long id) {
        LlmEndpoint e = get(id);
        if (analyses.existsByLlmEndpointId(id)) {
            throw Checks.inUse("LLM endpoint '" + e.getName() + "'");
        }
        repo.delete(e);
    }

    /** Sends a trivial prompt. Not transactional: the call may take up to timeoutSec. */
    public LlmTestResult test(long id) {
        LlmEndpoint e = get(id);
        long start = System.nanoTime();
        try {
            String reply = llm.chat(e.getBaseUrl(), e.getModel(), apiKey(e), e.getTimeoutSec(), TEST_PROMPT);
            long ms = (System.nanoTime() - start) / 1_000_000;
            String shown = reply.strip().length() > 80 ? reply.strip().substring(0, 80) + "…" : reply.strip();
            return new LlmTestResult(true, "Model " + e.getModel() + " replied: " + shown, ms);
        } catch (LlmException ex) {
            return new LlmTestResult(false, ex.getMessage(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Transactional(readOnly = true)
    public LlmEndpoint get(long id) {
        return repo.findById(id).orElseThrow(() -> Checks.notFound("LLM endpoint", id));
    }

    /** The decrypted apiKey, or null if none is stored. */
    String apiKey(LlmEndpoint e) {
        return e.getApiKeyEnc() == null ? null : cipher.decrypt(e.getApiKeyEnc());
    }

    private void apply(LlmEndpointUpsert req, String baseUrl, LlmEndpoint e) {
        e.setName(req.name());
        e.setBaseUrl(baseUrl);
        e.setModel(req.model());
        if (req.apiKey() != null) {
            e.setApiKeyEnc(cipher.encrypt(req.apiKey()));
        }
        e.setTimeoutSec(req.timeoutSec());
        e.setDefault(req.isDefault());
    }

    /** @return baseUrl without trailing '/' */
    private static String validate(LlmEndpointUpsert req) {
        if (req.apiKey() != null && req.clearApiKey()) {
            throw Checks.invalid("clearApiKey", "Send either apiKey or clearApiKey, not both");
        }
        String url = req.baseUrl().strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw Checks.invalid("baseUrl", "Not a valid URL");
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw Checks.invalid("baseUrl", "Must be an http(s) URL with a host, e.g. http://vllm:8000");
        }
        if (url.endsWith("/v1")) {
            throw Checks.invalid("baseUrl", "Leave out /v1: the backend calls {baseUrl}/v1/chat/completions");
        }
        return url;
    }
}
