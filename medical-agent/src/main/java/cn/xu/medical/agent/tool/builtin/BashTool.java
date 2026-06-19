package cn.xu.medical.agent.tool.builtin;

import cn.xu.medical.agent.permission.PermissionChain;
import cn.xu.medical.agent.permission.PermissionContext;
import cn.xu.medical.agent.permission.PermissionDecision;
import cn.xu.medical.agent.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

@Component
public class BashTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 10240;

    private final PermissionChain permissionChain;
    private final Path projectRoot;
    private final int maxTimeoutSeconds;
    private final int maxOutputChars;
    private final RedisTemplate<String, Object> redisTemplate;

    public BashTool(PermissionChain permissionChain,
                    @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot,
                    @Value("${agent.max-tool-timeout-seconds:30}") int maxTimeoutSeconds,
                    @Value("${agent.max-bash-output-chars:10240}") int maxOutputChars,
                    RedisTemplate<String, Object> redisTemplate) {
        this.permissionChain = permissionChain;
        this.projectRoot = Path.of(projectRoot);
        this.maxTimeoutSeconds = maxTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
        this.redisTemplate = redisTemplate;
    }

    @Tool(description = "在项目目录下执行 shell 命令。超时 " + DEFAULT_TIMEOUT_SECONDS + " 秒，输出截断 " + (MAX_OUTPUT_CHARS / 1024) + "KB。危险命令会被拦截")
    public ToolResult execute(
        @ToolParam(description = "要执行的 shell 命令") String command,
        @ToolParam(description = "超时秒数(默认" + DEFAULT_TIMEOUT_SECONDS + ")", required = false) Integer timeoutSeconds) {

        long start = System.currentTimeMillis();
        int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
            ? Math.min(timeoutSeconds, maxTimeoutSeconds)
            : DEFAULT_TIMEOUT_SECONDS;

        // Permission check
        PermissionDecision decision = permissionChain.evaluate(
            PermissionContext.builder()
                .toolName("BashTool")
                .command(command)
                .projectRoot(projectRoot)
                .readOnly(isReadOnlyCommand(command))
                .build()
        );
        if (decision.isDenied()) {
            return ToolResult.fail("权限拒绝: " + decision.getReason());
        }
        if (decision.needsConfirmation()) {
            // Return special result — caller should handle NEED_CONFIRM
            return ToolResult.builder()
                .success(false)
                .error("NEED_CONFIRM:" + decision.getConfirmToken() + ":" + decision.getConfirmPrompt())
                .build();
        }

        try {
            // Execute
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int chars = 0;
                while ((line = reader.readLine()) != null) {
                    if (chars < maxOutputChars) {
                        stdout.append(line).append("\n");
                        chars += line.length() + 1;
                    }
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String partial = stdout.length() > 0 ? stdout.toString() : "(无输出)";
                return ToolResult.builder()
                    .success(false)
                    .error("命令超时 (" + timeout + "s)")
                    .output(partial + "\n[超时被终止]")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
            }

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - start;
            String output = stdout.toString();
            if (output.length() > maxOutputChars) {
                output = output.substring(0, maxOutputChars) + "\n... 输出已截断";
            }

            // Cache result in Redis for potential reuse
            String cacheKey = "agent:bash:" + sha256(command).substring(0, 12);
            redisTemplate.opsForValue().set(cacheKey, output, Duration.ofMinutes(5));

            return ToolResult.builder()
                .success(exitCode == 0)
                .output(output.isEmpty() ? "(退出码: " + exitCode + ")" : output)
                .error(exitCode != 0 ? "退出码: " + exitCode : null)
                .durationMs(elapsed)
                .build();

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.builder()
                .success(false)
                .error("命令执行异常: " + e.getMessage())
                .durationMs(elapsed)
                .build();
        }
    }

    private boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return true;
        String cmd = command.trim().toLowerCase();
        return cmd.startsWith("ls ") || cmd.equals("ls") || cmd.startsWith("cat ")
            || cmd.startsWith("head ") || cmd.startsWith("tail ") || cmd.startsWith("wc ")
            || cmd.startsWith("find ") || cmd.startsWith("grep ") || cmd.equals("pwd")
            || cmd.startsWith("echo ") || cmd.startsWith("git status") || cmd.startsWith("git log")
            || cmd.startsWith("git diff") || cmd.startsWith("git branch") || cmd.equals("date")
            || cmd.equals("whoami") || cmd.startsWith("which ") || cmd.startsWith("type ")
            || cmd.startsWith("du ") || cmd.startsWith("df ") || cmd.startsWith("file ");
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
