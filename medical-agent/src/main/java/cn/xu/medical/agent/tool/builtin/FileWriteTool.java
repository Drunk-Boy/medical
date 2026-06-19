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
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FileWriteTool {

    private static final String CHECKPOINT_DIR = ".agent-checkpoints";

    private final PermissionChain permissionChain;
    private final Path projectRoot;

    public FileWriteTool(PermissionChain permissionChain,
                         @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
    }

    @Tool(description = "创建或覆盖文件。会自动在 .agent-checkpoints/ 目录下创建备份")
    public ToolResult writeFile(
        @ToolParam(description = "文件路径(相对于项目根目录)") String path,
        @ToolParam(description = "文件内容") String content) {

        long start = System.currentTimeMillis();

        try {
            Path filePath = projectRoot.resolve(path).normalize();

            // Permission check
            PermissionDecision decision = permissionChain.evaluate(
                PermissionContext.builder()
                    .toolName("FileWriteTool")
                    .targetPaths(new Path[]{filePath})
                    .projectRoot(projectRoot)
                    .readOnly(false)
                    .build()
            );
            if (decision.isDenied()) {
                return ToolResult.fail("权限拒绝: " + decision.getReason());
            }
            if (decision.needsConfirmation()) {
                return ToolResult.fail("需要用户确认: " + decision.getReason());
            }

            // Checkpoint: backup existing file
            if (Files.exists(filePath)) {
                checkpoint(filePath);
            }

            // Create parent directories
            Files.createDirectories(filePath.getParent());

            // Write file
            Files.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long elapsed = System.currentTimeMillis() - start;
            long size = Files.size(filePath);
            return ToolResult.builder()
                .success(true)
                .output("✓ 写入成功: " + path + " (" + formatSize(size) + ", " + elapsed + "ms)")
                .durationMs(elapsed)
                .build();

        } catch (IOException e) {
            return ToolResult.fail("写入失败 " + path + ": " + e.getMessage());
        }
    }

    private void checkpoint(Path filePath) throws IOException {
        Path checkpointDir = projectRoot.resolve(CHECKPOINT_DIR);
        Files.createDirectories(checkpointDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path backupPath = checkpointDir.resolve(filePath.getFileName() + "." + timestamp + ".bak");
        Files.copy(filePath, backupPath);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
