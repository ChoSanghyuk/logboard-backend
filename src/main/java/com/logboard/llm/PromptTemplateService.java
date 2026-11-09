package com.logboard.llm;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptTemplateService {

    private final PromptTemplateRepository repo;
    private final AnalysisRepository analyses;

    public PromptTemplateService(PromptTemplateRepository repo, AnalysisRepository analyses) {
        this.repo = repo;
        this.analyses = analyses;
    }

    @Transactional(readOnly = true)
    public List<PromptTemplateDto> list() {
        return repo.findAllByOrderByNameAsc().stream().map(PromptTemplateDto::of).toList();
    }

    @Transactional
    public PromptTemplateDto create(PromptTemplateUpsert req) {
        validate(req);
        if (repo.existsByName(req.name())) {
            throw Checks.duplicateName(req.name());
        }
        if (req.isDefault()) {
            repo.clearDefaultExcept(null);
        }
        PromptTemplate p = new PromptTemplate();
        apply(req, p);
        return PromptTemplateDto.of(repo.saveAndFlush(p));
    }

    @Transactional
    public PromptTemplateDto update(long id, PromptTemplateUpsert req) {
        PromptTemplate p = get(id);
        validate(req);
        if (repo.existsByNameAndIdNot(req.name(), id)) {
            throw Checks.duplicateName(req.name());
        }
        if (req.isDefault()) {
            repo.clearDefaultExcept(id); // before changing p: the bulk update flushes pending changes
        }
        apply(req, p);
        return PromptTemplateDto.of(repo.saveAndFlush(p));
    }

    @Transactional
    public void delete(long id) {
        PromptTemplate p = get(id);
        if (analyses.existsByPromptTemplateId(id)) {
            throw Checks.inUse("Prompt '" + p.getName() + "'");
        }
        repo.delete(p);
    }

    @Transactional(readOnly = true)
    public PromptTemplate get(long id) {
        return repo.findById(id).orElseThrow(() -> Checks.notFound("Prompt", id));
    }

    private static void apply(PromptTemplateUpsert req, PromptTemplate p) {
        p.setName(req.name());
        p.setContent(req.content());
        p.setDefault(req.isDefault());
    }

    private static void validate(PromptTemplateUpsert req) {
        if (!req.content().contains(PromptRenderer.ERROR_LOG)) {
            throw Checks.invalid("content", "Must contain the placeholder {{errorLog}}");
        }
    }
}
