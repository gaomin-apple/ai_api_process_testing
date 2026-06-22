package com.labway.aft.engine;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TemplateResolver {
    private static final Pattern VARIABLE =
            Pattern.compile("\\$\\{([\\p{L}_][\\p{L}\\p{N}_.-]*)}");

    public String resolve(String value, Map<String, String> variables) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.get(key);
            if (replacement == null) {
                String available = variables.keySet().stream()
                        .filter(variable -> variable.startsWith("run."))
                        .sorted()
                        .collect(Collectors.joining(", "));
                String suffix = available.isEmpty() ? "" : ". Available run variables: " + available;
                throw new IllegalArgumentException("Missing variable: " + key + suffix);
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
