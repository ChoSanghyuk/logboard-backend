package com.logboard.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code {{errorLog}}}, {@code {{serverName}}}, {@code {{errorTime}}}, {@code {{exceptionClass}}} in one
 * pass, so placeholder-like text inside the substituted log is left alone. Other {{…}} text is kept as is.
 */
final class PromptRenderer {

    static final String ERROR_LOG = "{{errorLog}}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(errorLog|serverName|errorTime|exceptionClass)}}");

    private PromptRenderer() {}

    static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }
}
