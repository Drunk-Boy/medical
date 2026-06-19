package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gate 2: Restrict file operations to within the project root directory.
 */
@Slf4j
@Component
public class DirectoryScopedGate implements PermissionGate {

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getTargetPaths() == null || ctx.getTargetPaths().length == 0) {
            return null; // no file paths involved, pass
        }

        Path projectRoot = ctx.getProjectRoot();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return null; // project root not set, skip check
        }

        try {
            Path realRoot = projectRoot.toRealPath();
            for (Path target : ctx.getTargetPaths()) {
                Path resolved = projectRoot.resolve(target).toRealPath();
                if (!resolved.startsWith(realRoot)) {
                    log.warn("DIRECTORY_SCOPED: path '{}' is outside project root '{}'", resolved, realRoot);
                    return PermissionDecision.deny(PermissionLevel.DIRECTORY_SCOPED,
                        "路径 " + target + " 超出项目目录范围");
                }
            }
        } catch (Exception e) {
            // If path doesn't exist yet (e.g., creating new file), resolve syntactically
            Path realRoot = projectRoot.toAbsolutePath().normalize();
            for (Path target : ctx.getTargetPaths()) {
                Path resolved = projectRoot.resolve(target).toAbsolutePath().normalize();
                if (!resolved.startsWith(realRoot)) {
                    return PermissionDecision.deny(PermissionLevel.DIRECTORY_SCOPED,
                        "路径 " + target + " 超出项目目录范围");
                }
            }
        }

        return null; // all paths within project root
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.DIRECTORY_SCOPED; }
}
