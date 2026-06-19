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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class FileReadTool {

    private static final int MAX_LINES = 2000;
    private static final long MAX_SIZE_BYTES = 1024 * 1024; // 1 MB

    private final PermissionChain permissionChain;
    private final Path projectRoot;

    public FileReadTool(PermissionChain permissionChain,
                        @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
    }

    @Tool(description = "读取文件内容，支持分页。offset: 起始行(0-based)，limit: 最大行数(默认2000)")
    public ToolResult readFile(
        @ToolParam(description = "文件路径(相对于项目根目录)") String path,
        @ToolParam(description = "起始行偏移(0-based)", required = false) Integer offset,
        @ToolParam(description = "最大读取行数", required = false) Integer limit) {

        long start = System.currentTimeMillis();

        try {
            Path filePath = projectRoot.resolve(path).toRealPath();

            // Permission check
            PermissionDecision decision = permissionChain.evaluate(
                PermissionContext.builder()
                    .toolName("FileReadTool")
                    .targetPaths(new Path[]{filePath})
                    .projectRoot(projectRoot)
                    .readOnly(true)
                    .build()
            );
            if (decision.isDenied()) {
                return ToolResult.fail("权限拒绝: " + decision.getReason());
            }

            // Size check
            long size = Files.size(filePath);
            if (size > MAX_SIZE_BYTES) {
                return ToolResult.fail("文件过大 (" + (size / 1024) + " KB)，超过 1MB 限制，请使用 offset/limit 分页读取");
            }

            List<String> allLines = Files.readAllLines(filePath);
            int startLine = offset != null ? offset : 0;
            int endLine = limit != null ? Math.min(startLine + limit, allLines.size()) : Math.min(startLine + MAX_LINES, allLines.size());

            if (startLine >= allLines.size()) {
                return ToolResult.ok("(文件共 " + allLines.size() + " 行，offset 超出范围)");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i < endLine; i++) {
                sb.append(String.format("%4d→%s%n", i + 1, allLines.get(i)));
            }

            boolean truncated = endLine < allLines.size();
            if (truncated) {
                sb.append(String.format("%n... 已截断 (第 %d-%d 行 / 共 %d 行)", startLine + 1, endLine, allLines.size()));
            }

            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.builder()
                .success(true)
                .output(sb.toString())
                .resultCount(endLine - startLine)
                .truncated(truncated)
                .durationMs(elapsed)
                .build();

        } catch (IOException e) {
            return ToolResult.fail("无法读取文件 " + path + ": " + e.getMessage());
        }
    }
}
