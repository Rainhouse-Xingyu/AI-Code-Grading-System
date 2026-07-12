package com.rainexis.backend.service.business;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/**
 * ZIP 结构分析服务
 * 解压学生提交的ZIP文件，提取其中的代码文件，生成包含文件树、内容、依赖关系的结构化JSON
 *
 * 安全限制：
 *   - 解压后总大小不超过 100MB
 *   - 单个文件不超过 1MB
 *   - 仅处理已知编程语言的代码文件
 *   - 自动跳过常见非代码目录（.git、node_modules等）
 */
@Service
public class ZipStructureService {
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".c", ".cpp", ".h", ".hpp", ".js", ".ts", ".go", ".rs", ".kt", ".swift"
    );
    private static final Set<String> BLACKLIST_DIRS = Set.of(
            "__macosx", ".git", ".idea", ".vscode", "node_modules", "target", "build", "dist", "out", "__pycache__"
    );
    private static final List<InjectionPattern> PROMPT_INJECTION_PATTERNS = List.of(
            new InjectionPattern("忽略评分标准", Pattern.compile("忽略.{0,12}(评分标准|rubric|扣分|要求)", Pattern.CASE_INSENSITIVE), "warning"),
            new InjectionPattern("忽略扣分", Pattern.compile("忽略.{0,12}扣分", Pattern.CASE_INSENSITIVE), "warning"),
            new InjectionPattern("给我满分", Pattern.compile("(给我|判|打|给).{0,8}(满分|100分|一百分|高分)", Pattern.CASE_INSENSITIVE), "warning"),
            new InjectionPattern("直接满分", Pattern.compile("(直接|必须|一定).{0,8}(满分|100分|一百分)", Pattern.CASE_INSENSITIVE), "warning"),
            new InjectionPattern("ignore rubric", Pattern.compile("\\b(ignore|disregard|bypass)\\b.{0,30}\\b(rubric|grading|deduction|score)\\b", Pattern.CASE_INSENSITIVE), "warning"),
            new InjectionPattern("give full score", Pattern.compile("\\b(give|grant|assign)\\b.{0,30}\\b(full|100|perfect|high)\\b.{0,20}\\b(score|grade|marks?)\\b", Pattern.CASE_INSENSITIVE), "warning")
    );
    private static final long MAX_TOTAL_UNCOMPRESSED = 100L * 1024 * 1024;
    private static final long MAX_SINGLE_FILE = 1024L * 1024;
    private static final List<Charset> CODE_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK"),
            Charset.forName("Big5"),
            Charset.forName("ISO-8859-1")
    );
    private final ObjectMapper objectMapper;

    public ZipStructureService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 分析ZIP文件：遍历条目 → 过滤代码文件 → 读取内容 → 生成结构JSON和依赖图 */
    public StructureResult analyze(Path zipPath, String language) {
        try {
            return analyzeWithCharset(zipPath, language, StandardCharsets.UTF_8);
        } catch (BusinessException ex) {
            if (isMalformedZipNameError(ex)) {
                return analyzeWithCharset(zipPath, language, Charset.forName("GBK"));
            }
            throw ex;
        }
    }

    private StructureResult analyzeWithCharset(Path zipPath, String language, Charset zipCharset) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long[] totalSize = {0L};
        try (ZipInputStream zip = new ZipInputStream(java.nio.file.Files.newInputStream(zipPath), zipCharset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = normalizeZipPath(entry.getName());
                if (entry.isDirectory() || entryName.isBlank() || inBlacklistedDirectory(entryName)) {
                    continue;
                }
                String extension = extensionOf(entryName);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    totalSize[0] += read;
                    if (totalSize[0] > MAX_TOTAL_UNCOMPRESSED) {
                        throw BusinessException.badRequest("ZIP 解压后总大小不能超过 100MB");
                    }
                    if (buffer.size() <= MAX_SINGLE_FILE) {
                        buffer.write(chunk, 0, read);
                    }
                }
                if (!CODE_EXTENSIONS.contains(extension)) {
                    warnings.add("跳过非代码文件: " + entryName);
                    continue;
                }
                if (buffer.size() > MAX_SINGLE_FILE) {
                    warnings.add("跳过超过 1MB 的代码文件: " + entryName);
                    continue;
                }
                DecodedContent decoded = decodeCodeContent(buffer.toByteArray());
                if (!StandardCharsets.UTF_8.equals(decoded.charset())) {
                    warnings.add("代码文件已从 " + decoded.charset().displayName() + " 转为 UTF-8: " + entryName);
                }
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", entryName);
                file.put("language", detectLanguage(extension, language));
                file.put("size", buffer.size());
                file.put("encoding", decoded.charset().displayName());
                file.put("lines", decoded.content().isBlank() ? 0 : decoded.content().split("\\R", -1).length);
                file.put("content", decoded.content());
                files.add(file);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            if (isMalformedInput(ex)) {
                throw BusinessException.badRequest("ZIP 文件名编码异常，请使用 UTF-8 或 GBK/Windows 默认编码重新压缩后上传: " + ex.getMessage());
            }
            throw BusinessException.badRequest("ZIP 读取失败: " + ex.getMessage());
        }
        if (files.isEmpty()) {
            throw BusinessException.badRequest("ZIP 中未发现受支持的代码文件");
        }
        files.sort(Comparator.comparing(item -> item.get("path").toString()));
        Map<String, Object> securityScan = scanPromptInjection(files);
        if (Boolean.TRUE.equals(securityScan.get("prompt_injection_detected"))) {
            warnings.add("检测到疑似评分操纵话术，请教师复核 security_scan");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("total_files", files.size());
        root.put("total_lines", files.stream().mapToInt(item -> ((Number) item.get("lines")).intValue()).sum());
        root.put("language", language);
        root.put("structure_summary", buildSummary(files));
        root.put("file_tree", files);
        root.put("dependency_graph", buildDependencyGraph(files));
        root.put("security_scan", securityScan);
        root.put("warnings", warnings);
        try {
            return new StructureResult(objectMapper.writeValueAsString(root), files.size());
        } catch (Exception ex) {
            throw new BusinessException(500, "结构化 JSON 生成失败: " + ex.getMessage());
        }
    }

    private boolean isMalformedZipNameError(BusinessException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("ZIP 文件名编码异常");
    }

    private boolean isMalformedInput(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof MalformedInputException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("malformed input")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private DecodedContent decodeCodeContent(byte[] bytes) {
        for (Charset charset : CODE_CHARSETS) {
            try {
                String content = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                return new DecodedContent(content, charset);
            } catch (CharacterCodingException ignored) {
                // Try the next common source encoding before falling back to replacement characters.
            }
        }
        return new DecodedContent(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** 路径标准化，阻止目录遍历攻击（../ / 开头等） */
    private String normalizeZipPath(String name) {
        Path normalized = Paths.get(name).normalize();
        String value = normalized.toString().replace('\\', '/');
        if (value.startsWith("../") || value.equals("..") || value.startsWith("/")) {
            throw BusinessException.badRequest("ZIP 包含非法路径: " + name);
        }
        return value;
    }

    /** 检查路径是否在黑名单目录下 */
    private boolean inBlacklistedDirectory(String path) {
        for (String part : path.toLowerCase(Locale.ROOT).split("/")) {
            if (BLACKLIST_DIRS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private String extensionOf(String path) {
        int index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(index).toLowerCase(Locale.ROOT);
    }

    private String detectLanguage(String extension, String fallback) {
        return switch (extension) {
            case ".java" -> "java";
            case ".py" -> "python";
            case ".c", ".h" -> "c";
            case ".cpp", ".hpp" -> "cpp";
            case ".js" -> "javascript";
            case ".ts" -> "typescript";
            default -> fallback == null ? "unknown" : fallback;
        };
    }

    /** 扫描代码内容和文件名中试图操纵评分的提示注入话术 */
    private Map<String, Object> scanPromptInjection(List<Map<String, Object>> files) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> file : files) {
            String path = file.get("path").toString();
            appendPromptInjectionFindings(findings, path, 1, "path", path);
            String[] lines = file.get("content").toString().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                appendPromptInjectionFindings(findings, path, i + 1, "content", lines[i]);
                if (findings.size() >= 20) {
                    break;
                }
            }
            if (findings.size() >= 20) {
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prompt_injection_detected", !findings.isEmpty());
        result.put("finding_count", findings.size());
        result.put("findings", findings);
        return result;
    }

    private void appendPromptInjectionFindings(List<Map<String, Object>> findings,
                                               String path,
                                               int line,
                                               String source,
                                               String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (InjectionPattern pattern : PROMPT_INJECTION_PATTERNS) {
            Matcher matcher = pattern.pattern().matcher(text);
            if (matcher.find()) {
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("file", path);
                finding.put("line", line);
                finding.put("source", source);
                finding.put("keyword", pattern.keyword());
                finding.put("severity", pattern.severity());
                finding.put("excerpt", excerpt(text, matcher.start(), matcher.end()));
                findings.add(finding);
                return;
            }
        }
    }

    private String excerpt(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }

    /** 生成代码文件摘要文本 */
    private String buildSummary(List<Map<String, Object>> files) {
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> file : files) {
            summary.append("- ").append(file.get("path"))
                    .append(" (").append(file.get("lines")).append(" lines)")
                    .append('\n');
        }
        return summary.toString();
    }

    /** 分析代码文件间的导入、类、方法调用依赖关系（Java AST / Python / C / C++） */
    private Map<String, Object> buildDependencyGraph(List<Map<String, Object>> files) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        Map<String, JavaFileInfo> javaFiles = new LinkedHashMap<>();
        Map<String, JavaClassInfo> classIndex = new HashMap<>();
        Map<String, PythonFileInfo> pythonFiles = new LinkedHashMap<>();
        Map<String, String> pythonModuleIndex = new HashMap<>();
        Map<String, String> pythonFunctionIndex = new HashMap<>();
        Map<String, CFileInfo> cFiles = new LinkedHashMap<>();
        Map<String, String> cHeaderIndex = new HashMap<>();
        Map<String, String> cFunctionIndex = new HashMap<>();
        for (Map<String, Object> file : files) {
            String path = file.get("path").toString();
            String content = file.get("content").toString();
            String language = file.get("language").toString();
            if ("java".equals(language)) {
                JavaFileInfo info = parseJavaFile(path, content);
                javaFiles.put(path, info);
                for (JavaClassInfo clazz : info.classes()) {
                    classIndex.put(clazz.qualifiedName(), clazz);
                    classIndex.put(clazz.name(), clazz);
                }
                nodes.add(node(path, language, info.classes().stream().map(JavaClassInfo::qualifiedName).toList(), info.methods()));
            } else if ("python".equals(language)) {
                PythonFileInfo info = parsePythonFile(content);
                pythonFiles.put(path, info);
                pythonModuleIndex.put(pythonModuleName(path), path);
                pythonModuleIndex.put(fileBaseName(path), path);
                for (String function : info.functions()) {
                    pythonFunctionIndex.putIfAbsent(function, path);
                }
                nodes.add(node(path, language, info.classes(), info.functions()));
            } else if (isCLanguage(language)) {
                CFileInfo info = parseCFile(content);
                cFiles.put(path, info);
                if (isCHeader(path)) {
                    cHeaderIndex.put(Paths.get(path).getFileName().toString(), path);
                }
                for (String function : info.functions()) {
                    cFunctionIndex.putIfAbsent(function, path);
                }
                nodes.add(node(path, language, List.of(), info.functions()));
            } else {
                nodes.add(node(path, language, List.of(), List.of()));
            }
        }
        for (Map<String, Object> file : files) {
            String path = file.get("path").toString();
            String content = file.get("content").toString();
            String language = file.get("language").toString();
            if ("java".equals(language)) {
                appendJavaDependencies(path, javaFiles.get(path), classIndex, edges, dependencies);
            } else if ("python".equals(language)) {
                appendPythonDependencies(path, pythonFiles.get(path), pythonModuleIndex, pythonFunctionIndex, edges, dependencies);
            } else if (isCLanguage(language)) {
                appendCDependencies(path, cFiles.get(path), cHeaderIndex, cFunctionIndex, edges, dependencies);
            }
        }
        return Map.of(
                "nodes", nodes,
                "edges", edges,
                "dependencies", dependencies,
                "dependency_graph", renderDependencyGraph(dependencies)
        );
    }

    private JavaFileInfo parseJavaFile(String path, String content) {
        try {
            CompilationUnit unit = StaticJavaParser.parse(content);
            String packageName = unit.getPackageDeclaration().map(item -> item.getNameAsString()).orElse("");
            List<String> imports = unit.getImports().stream()
                    .map(item -> item.getNameAsString() + (item.isAsterisk() ? ".*" : ""))
                    .toList();
            List<JavaClassInfo> classes = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .map(item -> {
                        String name = item.getNameAsString();
                        String qualifiedName = packageName.isBlank() ? name : packageName + "." + name;
                        return new JavaClassInfo(path, name, qualifiedName);
                    })
                    .toList();
            List<String> methods = unit.findAll(MethodDeclaration.class).stream()
                    .map(MethodDeclaration::getNameAsString)
                    .distinct()
                    .toList();
            List<JavaCallInfo> methodCalls = unit.findAll(MethodCallExpr.class).stream()
                    .map(call -> new JavaCallInfo(
                            call.getNameAsString(),
                            call.getScope().map(Object::toString).orElse(""),
                            call.getBegin().map(position -> position.line).orElse(null)
                    ))
                    .toList();
            List<JavaCallInfo> constructors = unit.findAll(ObjectCreationExpr.class).stream()
                    .map(call -> new JavaCallInfo(
                            call.getType().getNameAsString(),
                            call.getType().getNameAsString(),
                            call.getBegin().map(position -> position.line).orElse(null)
                    ))
                    .toList();
            return new JavaFileInfo(path, packageName, imports, classes, methods, methodCalls, constructors, List.of());
        } catch (ParseProblemException ex) {
            return new JavaFileInfo(path, "", List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of("Java AST 解析失败: " + ex.getMessage()));
        }
    }

    private PythonFileInfo parsePythonFile(String content) {
        Pattern imports = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import\\s+([\\w.*,\\s]+)|import\\s+([\\w.,\\s]+))", Pattern.MULTILINE);
        Pattern classes = Pattern.compile("^\\s*class\\s+([A-Za-z_]\\w*)\\s*(?:\\(|:)", Pattern.MULTILINE);
        Pattern functions = Pattern.compile("^\\s*def\\s+([A-Za-z_]\\w*)\\s*\\(", Pattern.MULTILINE);
        Pattern calls = Pattern.compile("(?<!def\\s)(?<!class\\s)\\b([A-Za-z_]\\w*)\\s*\\(");
        Set<String> importNames = new LinkedHashSet<>();
        Set<String> classNames = new LinkedHashSet<>();
        Set<String> functionNames = new LinkedHashSet<>();
        Set<String> callNames = new LinkedHashSet<>();
        Matcher importMatcher = imports.matcher(content);
        while (importMatcher.find()) {
            if (importMatcher.group(1) != null) {
                importNames.add(importMatcher.group(1).trim());
                for (String name : importMatcher.group(2).split(",")) {
                    String imported = name.trim();
                    if (!imported.isBlank() && !"*".equals(imported)) {
                        importNames.add(importMatcher.group(1).trim() + "." + imported.split("\\s+as\\s+")[0].trim());
                    }
                }
            } else {
                for (String name : importMatcher.group(3).split(",")) {
                    String imported = name.trim();
                    if (!imported.isBlank()) {
                        importNames.add(imported.split("\\s+as\\s+")[0].trim());
                    }
                }
            }
        }
        collectMatches(classes, content, classNames);
        collectMatches(functions, content, functionNames);
        Matcher callMatcher = calls.matcher(stripPythonStrings(content));
        while (callMatcher.find()) {
            String name = callMatcher.group(1);
            if (!PYTHON_CALL_IGNORES.contains(name)) {
                callNames.add(name);
            }
        }
        return new PythonFileInfo(List.copyOf(importNames), List.copyOf(classNames), List.copyOf(functionNames), List.copyOf(callNames));
    }

    private void appendPythonDependencies(String path,
                                          PythonFileInfo info,
                                          Map<String, String> moduleIndex,
                                          Map<String, String> functionIndex,
                                          List<Map<String, Object>> edges,
                                          List<Map<String, Object>> dependencies) {
        if (info == null) {
            return;
        }
        for (String importName : info.imports()) {
            String targetFile = resolvePythonModule(importName, moduleIndex);
            addDependency(edges, dependencies, path, "", importName, targetFile, "import", "Python import " + importName);
        }
        for (String call : info.calls()) {
            String targetFile = functionIndex.getOrDefault(call, moduleIndex.get(call));
            if (targetFile != null && !targetFile.equals(path)) {
                addDependency(edges, dependencies, path, "", call, targetFile, "function_call", call + "()");
            }
        }
    }

    private CFileInfo parseCFile(String content) {
        String withoutComments = stripCComments(content);
        Pattern includes = Pattern.compile("^\\s*#\\s*include\\s+[<\"]([^>\"]+)[>\"]", Pattern.MULTILINE);
        Pattern functions = Pattern.compile("(?m)^\\s*(?!return\\b)(?:[A-Za-z_]\\w*|struct\\s+[A-Za-z_]\\w*)[\\w\\s*]*\\s+([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*(?:\\{|;)");
        Pattern calls = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
        Set<String> includeNames = new LinkedHashSet<>();
        Set<String> functionNames = new LinkedHashSet<>();
        Set<String> callNames = new LinkedHashSet<>();
        collectMatches(includes, withoutComments, includeNames);
        Matcher functionMatcher = functions.matcher(withoutComments);
        while (functionMatcher.find()) {
            String name = functionMatcher.group(1);
            if (!C_CALL_IGNORES.contains(name)) {
                functionNames.add(name);
            }
        }
        Matcher callMatcher = calls.matcher(withoutComments);
        while (callMatcher.find()) {
            String name = callMatcher.group(1);
            if (!C_CALL_IGNORES.contains(name) && !functionNames.contains(name)) {
                callNames.add(name);
            }
        }
        return new CFileInfo(List.copyOf(includeNames), List.copyOf(functionNames), List.copyOf(callNames));
    }

    private void appendCDependencies(String path,
                                     CFileInfo info,
                                     Map<String, String> headerIndex,
                                     Map<String, String> functionIndex,
                                     List<Map<String, Object>> edges,
                                     List<Map<String, Object>> dependencies) {
        if (info == null) {
            return;
        }
        for (String include : info.includes()) {
            String targetFile = headerIndex.get(include);
            addDependency(edges, dependencies, path, "", include, targetFile, "include", "#include " + include);
        }
        for (String call : info.calls()) {
            String targetFile = functionIndex.get(call);
            if (targetFile != null && !targetFile.equals(path)) {
                addDependency(edges, dependencies, path, "", call, targetFile, "function_call", call + "()");
            }
        }
    }

    private void appendJavaDependencies(String path,
                                        JavaFileInfo info,
                                        Map<String, JavaClassInfo> classIndex,
                                        List<Map<String, Object>> edges,
                                        List<Map<String, Object>> dependencies) {
        if (info == null) {
            return;
        }
        for (String importName : info.imports()) {
            JavaClassInfo target = classIndex.get(importName);
            addDependency(edges, dependencies, path, primaryClass(info), importName,
                    target == null ? null : target.path(), "import", "Java import " + importName);
        }
        for (JavaCallInfo call : info.methodCalls()) {
            JavaClassInfo target = resolveJavaTarget(call.scope(), info, classIndex);
            if (target != null && !target.path().equals(path)) {
                addDependency(edges, dependencies, path, primaryClass(info), target.qualifiedName(),
                        target.path(), "method_call", call.name() + "()");
            }
        }
        for (JavaCallInfo constructor : info.constructors()) {
            JavaClassInfo target = resolveJavaTarget(constructor.scope(), info, classIndex);
            if (target != null && !target.path().equals(path)) {
                addDependency(edges, dependencies, path, primaryClass(info), target.qualifiedName(),
                        target.path(), "object_creation", "new " + constructor.name());
            }
        }
        for (String warning : info.warnings()) {
            addDependency(edges, dependencies, path, primaryClass(info), "parse-warning", null, "warning", warning);
        }
    }

    private JavaClassInfo resolveJavaTarget(String scope, JavaFileInfo currentFile, Map<String, JavaClassInfo> classIndex) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        JavaClassInfo direct = classIndex.get(scope);
        if (direct != null) {
            return direct;
        }
        for (String importName : currentFile.imports()) {
            if (importName.endsWith("." + scope)) {
                return classIndex.get(importName);
            }
        }
        return classIndex.get(currentFile.packageName().isBlank() ? scope : currentFile.packageName() + "." + scope);
    }

    private String primaryClass(JavaFileInfo info) {
        return info.classes().isEmpty() ? "" : info.classes().get(0).qualifiedName();
    }

    private Map<String, Object> node(String path, String language, List<String> classes, List<String> methods) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", path);
        node.put("language", language);
        node.put("classes", classes);
        node.put("methods", methods);
        return node;
    }

    private void collectMatches(Pattern pattern, String content, Set<String> values) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
    }

    private boolean isCLanguage(String language) {
        return "c".equals(language) || "cpp".equals(language);
    }

    private boolean isCHeader(String path) {
        String extension = extensionOf(path);
        return ".h".equals(extension) || ".hpp".equals(extension);
    }

    private String fileBaseName(String path) {
        String fileName = Paths.get(path).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private String pythonModuleName(String path) {
        String withoutExtension = path.endsWith(".py") ? path.substring(0, path.length() - 3) : path;
        return withoutExtension.replace('/', '.');
    }

    private String resolvePythonModule(String importName, Map<String, String> moduleIndex) {
        String current = importName;
        while (!current.isBlank()) {
            String target = moduleIndex.get(current);
            if (target != null) {
                return target;
            }
            int dot = current.lastIndexOf('.');
            if (dot < 0) {
                return moduleIndex.get(current);
            }
            current = current.substring(0, dot);
        }
        return null;
    }

    private String stripPythonStrings(String content) {
        return content
                .replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"")
                .replaceAll("(?s)'''.*?'''", "''")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("'(?:\\\\.|[^'\\\\])*'", "''");
    }

    private String stripCComments(String content) {
        return content
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", "");
    }

    private void addDependency(List<Map<String, Object>> edges,
                               List<Map<String, Object>> dependencies,
                               String fromFile,
                               String fromClass,
                               String toClass,
                               String toFile,
                               String type,
                               String detail) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", fromFile);
        edge.put("to", toFile == null ? toClass : toFile);
        edge.put("type", type);
        edges.add(edge);

        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("from_file", fromFile);
        dependency.put("from_class", fromClass == null ? "" : fromClass);
        dependency.put("to_file", toFile);
        dependency.put("to_class", toClass);
        dependency.put("type", type);
        dependency.put("detail", detail);
        dependencies.add(dependency);
    }

    private String renderDependencyGraph(List<Map<String, Object>> dependencies) {
        if (dependencies.isEmpty()) {
            return "未检测到跨文件依赖";
        }
        StringBuilder graph = new StringBuilder();
        for (Map<String, Object> dependency : dependencies) {
            graph.append(dependency.get("from_file"))
                    .append(" --")
                    .append(dependency.get("type"))
                    .append("--> ")
                    .append(dependency.get("to_file") == null ? dependency.get("to_class") : dependency.get("to_file"))
                    .append(" (")
                    .append(dependency.get("detail"))
                    .append(")")
                    .append('\n');
        }
        return graph.toString();
    }

    private record JavaFileInfo(String path,
                                String packageName,
                                List<String> imports,
                                List<JavaClassInfo> classes,
                                List<String> methods,
                                List<JavaCallInfo> methodCalls,
                                List<JavaCallInfo> constructors,
                                List<String> warnings) {
    }

    private record JavaClassInfo(String path, String name, String qualifiedName) {
    }

    private record JavaCallInfo(String name, String scope, Integer line) {
    }

    private record PythonFileInfo(List<String> imports,
                                  List<String> classes,
                                  List<String> functions,
                                  List<String> calls) {
    }

    private record CFileInfo(List<String> includes,
                             List<String> functions,
                             List<String> calls) {
    }

    private record InjectionPattern(String keyword, Pattern pattern, String severity) {
    }

    private record DecodedContent(String content, Charset charset) {
    }

    private static final Set<String> PYTHON_CALL_IGNORES = Set.of(
            "if", "for", "while", "with", "return", "print", "len", "range", "str", "int", "float", "list", "dict",
            "set", "tuple", "super", "isinstance", "enumerate", "open"
    );

    private static final Set<String> C_CALL_IGNORES = Set.of(
            "if", "for", "while", "switch", "return", "sizeof", "printf", "scanf", "malloc", "free"
    );

    /** 结构化分析结果 */
    public record StructureResult(String structureJson, int fileCount) {
    }
}
