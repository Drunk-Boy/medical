package cn.xu.medical.agent.tool.builtin;

import cn.xu.medical.agent.permission.PermissionChain;
import cn.xu.medical.agent.permission.PermissionContext;
import cn.xu.medical.agent.permission.PermissionDecision;
import cn.xu.medical.agent.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class GrepTool {

    private static final int MAX_RESULTS = 200;
    private static final int MAX_LINE_LENGTH = 500;
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", ".idea", "target", "build", "__pycache__"
    );

    private final PermissionChain permissionChain;
    private final Path projectRoot;

    public GrepTool(PermissionChain permissionChain,
                    @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
    }

    @Tool(description = "按正则表达式搜索文件内容。返回 path:行号:匹配行 格式，最多200条。跳过 .gitignore 中的路径")
    public ToolResult grep(
        @ToolParam(description = "正则表达式 (RE2/Java 语法)") String pattern,
        @ToolParam(description = "搜索路径(文件或目录，相对于项目根目录，默认='.')", required = false) String path) {

        long start = System.currentTimeMillis();

        // Permission check (read-only)
        PermissionDecision decision = permissionChain.evaluate(
            PermissionContext.builder()
                .toolName("GrepTool")
                .targetPaths(new Path[]{projectRoot})
                .projectRoot(projectRoot)
                .readOnly(true)
                .build()
        );
        if (decision.isDenied()) {
            return ToolResult.fail("权限拒绝: " + decision.getReason());
        }

        try {
            Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            Path searchRoot = (path != null && !path.equals("."))
                ? projectRoot.resolve(path)
                : projectRoot;

            if (!Files.exists(searchRoot)) {
                return ToolResult.fail("路径不存在: " + path);
            }

            List<String> matches = new ArrayList<>();

            if (Files.isRegularFile(searchRoot)) {
                // Search single file
                searchFile(searchRoot, regex, matches);
            } else {
                // Search directory recursively
                Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matches.size() >= MAX_RESULTS) {
                            return FileVisitResult.TERMINATE;
                        }
                        // Skip binary files by extension
                        String name = file.getFileName().toString().toLowerCase();
                        if (isBinary(name)) return FileVisitResult.CONTINUE;
                        searchFile(file, regex, matches);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            StringBuilder sb = new StringBuilder();
            String rootStr = projectRoot.toString();
            for (String m : matches) {
                sb.append(rootStr).append(FileSystems.getDefault().getSeparator()).append(m).append("\n");
            }

            long elapsed = System.currentTimeMillis() - start;
            boolean truncated = matches.size() >= MAX_RESULTS;
            if (truncated) {
                sb.append("\n... 结果已截断 (显示前 ").append(MAX_RESULTS).append(" 条)");
            }
            if (matches.isEmpty()) {
                sb.append("无匹配结果");
            }

            return ToolResult.builder()
                .success(true)
                .output(sb.toString())
                .resultCount(matches.size())
                .truncated(truncated)
                .durationMs(elapsed)
                .build();

        } catch (PatternSyntaxException e) {
            return ToolResult.fail("正则表达式语法错误: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.fail("Grep 搜索失败: " + e.getMessage());
        }
    }

    private void searchFile(Path file, Pattern regex, List<String> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            Path relative = projectRoot.relativize(file);
            for (int i = 0; i < lines.size() && results.size() < MAX_RESULTS; i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    String line = lines.get(i);
                    if (line.length() > MAX_LINE_LENGTH) {
                        line = line.substring(0, MAX_LINE_LENGTH) + "...";
                    }
                    results.add(relative + ":" + (i + 1) + ":" + line);
                }
            }
        } catch (IOException ignored) {
            // skip unreadable files
        }
    }

    private boolean isBinary(String filename) {
        return filename.endsWith(".class") || filename.endsWith(".jar")
            || filename.endsWith(".war") || filename.endsWith(".ear")
            || filename.endsWith(".png") || filename.endsWith(".jpg")
            || filename.endsWith(".gif") || filename.endsWith(".ico")
            || filename.endsWith(".pdf") || filename.endsWith(".zip")
            || filename.endsWith(".tar") || filename.endsWith(".gz")
            || filename.endsWith(".exe") || filename.endsWith(".dll")
            || filename.endsWith(".so") || filename.endsWith(".dylib");
    }
}
