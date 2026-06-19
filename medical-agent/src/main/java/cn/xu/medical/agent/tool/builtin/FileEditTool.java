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
public class FileEditTool {

    private static final String CHECKPOINT_DIR = ".agent-checkpoints";

    private final PermissionChain permissionChain;
    private final Path projectRoot;

    public FileEditTool(PermissionChain permissionChain,
                        @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
    }

    @Tool(description = "精确字符串替换编辑文件。old_string 必须在文件中唯一匹配一次，替换为 new_string")
    public ToolResult editFile(
        @ToolParam(description = "文件路径(相对于项目根目录)") String path,
        @ToolParam(description = "要替换的原字符串(必须唯一匹配)") String oldString,
        @ToolParam(description = "替换后的新字符串") String newString) {

        long start = System.currentTimeMillis();

        try {
            Path filePath = projectRoot.resolve(path).toRealPath();

            // Permission check
            PermissionDecision decision = permissionChain.evaluate(
                PermissionContext.builder()
                    .toolName("FileEditTool")
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

            String content = Files.readString(filePath);

            // Count occurrences
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(oldString, idx)) != -1) {
                count++;
                idx += oldString.length();
            }

            if (count == 0) {
                return ToolResult.fail("未找到匹配字符串: old_string 在文件中不存在");
            }
            if (count > 1) {
                return ToolResult.fail("old_string 匹配了 " + count + " 次，必须唯一匹配。请增加更多上下文使其唯一");
            }

            // Checkpoint
            checkpoint(filePath);

            // Replace
            String newContent = content.replace(oldString, newString);
            Files.writeString(filePath, newContent, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.builder()
                .success(true)
                .output("✓ 编辑成功: " + path + " (1 处替换, " + elapsed + "ms)")
                .durationMs(elapsed)
                .build();

        } catch (IOException e) {
            return ToolResult.fail("编辑失败 " + path + ": " + e.getMessage());
        }
    }

    private void checkpoint(Path filePath) throws IOException {
        Path checkpointDir = projectRoot.resolve(CHECKPOINT_DIR);
        Files.createDirectories(checkpointDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path backupPath = checkpointDir.resolve(filePath.getFileName() + "." + timestamp + ".bak");
        Files.copy(filePath, backupPath);
    }
}
