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
import java.util.stream.Collectors;

@Component
public class GlobTool {

    private static final int MAX_RESULTS = 500;
    private static final Set<String> DEFAULT_IGNORE = Set.of(
        ".git", "node_modules", ".idea", "target", "build", "__pycache__",
        ".svn", ".hg", ".DS_Store", "Thumbs.db"
    );

    private final PermissionChain permissionChain;
    private final Path projectRoot;

    public GlobTool(PermissionChain permissionChain,
                    @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
    }

    @Tool(description = "按 glob 模式递归查找文件。支持 ** 递归匹配。自动跳过 .gitignore 中的文件")
    public ToolResult glob(
        @ToolParam(description = "Glob 模式 (如 '**/*.java', 'src/**/*.ts')") String pattern) {

        long start = System.currentTimeMillis();

        // Permission check (read-only)
        PermissionDecision decision = permissionChain.evaluate(
            PermissionContext.builder()
                .toolName("GlobTool")
                .targetPaths(new Path[]{projectRoot})
                .projectRoot(projectRoot)
                .readOnly(true)
                .build()
        );
        if (decision.isDenied()) {
            return ToolResult.fail("权限拒绝: " + decision.getReason());
        }

        try {
            List<String> results = new ArrayList<>();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (DEFAULT_IGNORE.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = projectRoot.relativize(file);
                    if (matcher.matches(relative)) {
                        results.add(relative.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            StringBuilder sb = new StringBuilder();
            for (String r : results) {
                sb.append(r).append("\n");
            }

            long elapsed = System.currentTimeMillis() - start;
            boolean truncated = results.size() >= MAX_RESULTS;
            if (truncated) {
                sb.append("\n... 结果已截断 (显示前 ").append(MAX_RESULTS).append(" 条)");
            }

            return ToolResult.builder()
                .success(true)
                .output(sb.isEmpty() ? "无匹配文件" : sb.toString())
                .resultCount(results.size())
                .truncated(truncated)
                .durationMs(elapsed)
                .build();

        } catch (IOException e) {
            return ToolResult.fail("Glob 搜索失败: " + e.getMessage());
        }
    }
}
