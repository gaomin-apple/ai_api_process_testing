package com.labway.aft.server;

import org.springframework.stereotype.Component;

import com.labway.aft.domain.EndpointDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class JavaProjectScanner {
    private static final int MAX_FILES = 240;
    private static final int MAX_FILE_CHARS = 7_000;
    private static final int MAX_TOTAL_CHARS = 240_000;

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private)?\\s*(?:static\\s+)?[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*\\{"
    );
    private static final Pattern MAPPING_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*(\\([^)]*\\))?",
            Pattern.MULTILINE
    );
    private static final Pattern METHOD_MAPPING_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*(\\([^)]*\\))?"
                    + "[\\s\\r\\n]*(?:@\\w+(?:\\([^)]*\\))?[\\s\\r\\n]*)*"
                    + "(?:public|protected|private)?\\s*(?:static\\s+)?[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE
    );
    private static final Pattern CALL_PATTERN = Pattern.compile("\\b([a-z]\\w*)\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern PATH_VALUE_PATTERN = Pattern.compile(
            "(?:path|value)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\""
    );
    private static final Pattern REQUEST_METHOD_PATTERN = Pattern.compile("RequestMethod\\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)");
    private static final Pattern PATH_PARAMETER_PATTERN = Pattern.compile("\\{([^}/]+)}");

    public ScanResult scan(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("Java project path is required");
        }
        Path root = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Java project path is not a directory: " + root);
        }

        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(root)) {
            javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !isIgnored(root.relativize(path)))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_FILES)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to scan Java project: " + exception.getMessage(), exception);
        }

        List<JavaFileSummary> summaries = new ArrayList<>();
        int totalChars = 0;
        for (Path file : javaFiles) {
            if (totalChars >= MAX_TOTAL_CHARS) {
                break;
            }
            String source = readTruncated(file);
            int remaining = MAX_TOTAL_CHARS - totalChars;
            if (source.length() > remaining) {
                source = source.substring(0, remaining);
            }
            totalChars += source.length();
            summaries.add(summarize(root, file, source));
        }
        List<DiscoveredEndpoint> discoveredEndpoints = summaries.stream()
                .flatMap(summary -> discoverEndpoints(summary).stream())
                .toList();
        return new ScanResult(root.toString(), javaFiles.size(), summaries.size(), totalChars, summaries, discoveredEndpoints);
    }

    public List<EndpointDefinition> endpointDefinitions(String projectId, ScanResult scan) {
        return scan.discoveredEndpoints().stream()
                .map(endpoint -> new EndpointDefinition(
                        stableId(projectId + " " + endpoint.method() + " " + endpoint.path()),
                        projectId,
                        endpoint.operationId(),
                        endpoint.method(),
                        endpoint.path(),
                        endpoint.summary(),
                        endpoint.tags(),
                        pathParameters(endpoint.path()),
                        null,
                        null,
                        true
                ))
                .toList();
    }

    private static boolean isIgnored(Path relative) {
        String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/target/")
                || normalized.contains("/build/")
                || normalized.contains("/out/")
                || normalized.contains("/node_modules/")
                || normalized.contains("/.git/")
                || normalized.startsWith("target/")
                || normalized.startsWith("build/")
                || normalized.startsWith("out/")
                || normalized.startsWith(".git/");
    }

    private static String readTruncated(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            return source.length() <= MAX_FILE_CHARS ? source : source.substring(0, MAX_FILE_CHARS);
        } catch (IOException exception) {
            return "";
        }
    }

    private static JavaFileSummary summarize(Path root, Path file, String source) {
        String packageName = firstGroup(PACKAGE_PATTERN, source);
        String className = firstGroup(CLASS_PATTERN, source, 2);
        return new JavaFileSummary(
                root.relativize(file).toString().replace('\\', '/'),
                packageName,
                className,
                mappings(source),
                methods(source),
                calls(source),
                source
        );
    }

    private static List<DiscoveredEndpoint> discoverEndpoints(JavaFileSummary summary) {
        if (summary.source() == null || summary.source().isBlank()) {
            return List.of();
        }
        String classBasePath = classBasePath(summary.source());
        List<DiscoveredEndpoint> endpoints = new ArrayList<>();
        Matcher matcher = METHOD_MAPPING_PATTERN.matcher(summary.source());
        while (matcher.find() && endpoints.size() < 80) {
            String annotation = matcher.group(1);
            String arguments = matcher.group(2);
            String methodName = matcher.group(3);
            String httpMethod = httpMethod(annotation, arguments);
            String methodPath = pathValue(arguments);
            String path = normalizePath(classBasePath, methodPath);
            endpoints.add(new DiscoveredEndpoint(
                    httpMethod,
                    path,
                    methodName,
                    (summary.className() == null ? "" : summary.className() + ".") + methodName,
                    List.of(summary.className() == null ? "Java" : summary.className()),
                    summary.path()
            ));
        }
        return endpoints;
    }

    private static String classBasePath(String source) {
        int classIndex = source.indexOf(" class ");
        if (classIndex < 0) {
            classIndex = source.indexOf(" record ");
        }
        String prefix = classIndex < 0 ? source : source.substring(0, classIndex);
        Matcher matcher = MAPPING_PATTERN.matcher(prefix);
        String path = "";
        while (matcher.find()) {
            if ("RequestMapping".equals(matcher.group(1))) {
                path = pathValue(matcher.group(2));
            }
        }
        return path == null ? "" : path;
    }

    private static String httpMethod(String annotation, String arguments) {
        return switch (annotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> {
                Matcher matcher = REQUEST_METHOD_PATTERN.matcher(arguments == null ? "" : arguments);
                yield matcher.find() ? matcher.group(1) : "GET";
            }
        };
    }

    private static String pathValue(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        Matcher matcher = PATH_VALUE_PATTERN.matcher(arguments);
        if (!matcher.find()) {
            return "";
        }
        String first = matcher.group(1);
        return first != null ? first : matcher.group(2);
    }

    private static String normalizePath(String basePath, String methodPath) {
        String base = basePath == null ? "" : basePath.trim();
        String method = methodPath == null ? "" : methodPath.trim();
        String combined = ("/" + base + "/" + method).replace('\\', '/').replaceAll("/+", "/");
        if (combined.length() > 1 && combined.endsWith("/")) {
            combined = combined.substring(0, combined.length() - 1);
        }
        return combined.isBlank() ? "/" : combined;
    }

    private static List<EndpointDefinition.ParameterDefinition> pathParameters(String path) {
        Map<String, EndpointDefinition.ParameterDefinition> parameters = new LinkedHashMap<>();
        Matcher matcher = PATH_PARAMETER_PATTERN.matcher(path);
        while (matcher.find()) {
            String name = matcher.group(1);
            parameters.put(name, new EndpointDefinition.ParameterDefinition(name, "path", true, null));
        }
        return List.copyOf(parameters.values());
    }

    private static String firstGroup(Pattern pattern, String source) {
        return firstGroup(pattern, source, 1);
    }

    private static String firstGroup(Pattern pattern, String source, int group) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static List<String> mappings(String source) {
        List<String> values = new ArrayList<>();
        Matcher matcher = MAPPING_PATTERN.matcher(source);
        while (matcher.find() && values.size() < 40) {
            values.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
        return values;
    }

    private static List<String> methods(String source) {
        List<String> values = new ArrayList<>();
        Matcher matcher = METHOD_PATTERN.matcher(source);
        while (matcher.find() && values.size() < 80) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static List<String> calls(String source) {
        List<String> values = new ArrayList<>();
        Matcher matcher = CALL_PATTERN.matcher(source);
        while (matcher.find() && values.size() < 120) {
            String call = matcher.group(1) + "." + matcher.group(2);
            if (!values.contains(call)) {
                values.add(call);
            }
        }
        return values;
    }

    public record ScanResult(
            String root,
            int javaFileCandidates,
            int includedFiles,
            int includedCharacters,
            List<JavaFileSummary> files,
            List<DiscoveredEndpoint> discoveredEndpoints
    ) {
    }

    public record JavaFileSummary(
            String path,
            String packageName,
            String className,
            List<String> mappings,
            List<String> methods,
            List<String> calls,
            String source
    ) {
    }

    public record DiscoveredEndpoint(
            String method,
            String path,
            String operationId,
            String summary,
            List<String> tags,
            String sourceFile
    ) {
    }

    private static String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "java_ep_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
