package cn.xu.medical.agent.tool.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Three-level MCP configuration merger.
 *
 * Level 1: Managed (organization-wide) — lowest priority
 * Level 2: Project (.mcp.json in project root) — medium priority
 * Level 3: User (~/.mcp.json in user home) — highest priority
 *
 * Latter overrides former. Arrays are union-merged. Maps are key-overlayed.
 */
@Slf4j
@Component
public class McpConfigMerger {

    /**
     * Merge multiple config layers into one effective config.
     * Configs should be ordered from lowest to highest priority.
     */
    public McpServerConfig merge(List<McpServerConfig> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("At least one config layer required");
        }

        McpServerConfig result = new McpServerConfig();
        result.setName(layers.getFirst().getName());
        result.setEnabled(true);

        for (McpServerConfig layer : layers) {
            // Scalar fields: latter non-null overwrites former
            if (layer.getTransport() != null) result.setTransport(layer.getTransport());
            if (layer.getUrl() != null) result.setUrl(EnvExpander.expand(layer.getUrl()));
            if (layer.getCommand() != null) result.setCommand(layer.getCommand());
            if (layer.getScope() != null) result.setScope(layer.getScope());
            if (layer.getTimeoutMs() > 0) result.setTimeoutMs(layer.getTimeoutMs());
            if (layer.getToolTimeoutMs() > 0) result.setToolTimeoutMs(layer.getToolTimeoutMs());

            // Boolean: latter non-null overwrites
            if (layer.isEnabled() != result.isEnabled()) result.setEnabled(layer.isEnabled());
            if (layer.isAlwaysLoad()) result.setAlwaysLoad(true);

            // Array fields (e.g., args): union merge
            result.setArgs(mergeLists(result.getArgs(), layer.getArgs()));

            // Map fields: overlay
            result.setEnv(mergeMaps(result.getEnv(), layer.getEnv()));
            result.setHeaders(mergeMaps(result.getHeaders(), layer.getHeaders()));
        }

        HeadersInjector.inject(result);
        return result;
    }

    private <T> List<T> mergeLists(List<T> base, List<T> overlay) {
        if (overlay == null || overlay.isEmpty()) return base != null ? base : List.of();
        if (base == null || base.isEmpty()) return new ArrayList<>(overlay);
        Set<T> merged = new LinkedHashSet<>(base);
        merged.addAll(overlay);
        return new ArrayList<>(merged);
    }

    private Map<String, String> mergeMaps(Map<String, String> base, Map<String, String> overlay) {
        if (overlay == null || overlay.isEmpty()) return base != null ? base : Map.of();
        if (base == null || base.isEmpty()) return new LinkedHashMap<>(overlay);
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }

    /**
     * Env expander (inline — delegates to EnvExpander)
     */
    public static class EnvExpander {
        public static String expand(String value) {
            if (value == null) return null;
            // Simple ${VAR} expansion
            return System.getenv().entrySet().stream()
                .reduce(value,
                    (s, e) -> s.replace("${" + e.getKey() + "}", e.getValue()),
                    (a, b) -> b);
        }
    }

    /**
     * Headers injector (inline)
     */
    public static class HeadersInjector {
        public static void inject(McpServerConfig config) {
            Map<String, String> headers = config.getHeaders();
            if (headers == null) {
                headers = new LinkedHashMap<>();
                config.setHeaders(headers);
            }
            headers.putIfAbsent("X-Agent-Version", "1.0.0");
            headers.putIfAbsent("User-Agent", "Medical-Agent/1.0");
        }
    }
}
