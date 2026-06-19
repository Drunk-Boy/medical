package cn.xu.medical.controller;

import cn.xu.medical.agent.common.dto.AgentEvent;
import cn.xu.medical.agent.common.entity.AgentSession;
import cn.xu.medical.agent.context.AgentLoopService;
import cn.xu.medical.agent.context.AgentSessionService;
import cn.xu.medical.agent.context.ContextManager;
import cn.xu.medical.agent.permission.ExplicitConfirmGate;
import cn.xu.medical.agent.permission.PermissionChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentLoopService agentLoopService;
    private final AgentSessionService sessionService;
    private final ContextManager contextManager;
    private final PermissionChain permissionChain;
    private final ExplicitConfirmGate explicitConfirmGate;

    // ==================== Session Management ====================

    @PostMapping("/session")
    public AgentSession createSession(@RequestParam(defaultValue = "New Session") String title) {
        return sessionService.create(title, System.getProperty("user.dir"), "deepseek-chat");
    }

    @GetMapping("/sessions")
    public List<AgentSession> listSessions() {
        return sessionService.listActive();
    }

    @GetMapping("/{sessionId}")
    public AgentSession getSession(@PathVariable String sessionId) {
        return sessionService.getById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        sessionService.delete(sessionId);
        permissionChain.clearSession(sessionId);
        return Map.of("status", "deleted");
    }

    // ==================== Message Processing ====================

    /**
     * Send message and stream response via SSE.
     */
    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> streamMessage(
        @PathVariable String sessionId,
        @RequestParam String message) {

        log.info("Streaming message for session {}: {}", sessionId, message);
        return agentLoopService.processMessage(sessionId, message);
    }

    // ==================== Context ====================

    @GetMapping("/{sessionId}/context")
    public Map<String, Object> getContextUsage(@PathVariable String sessionId) {
        return contextManager.getContextUsage(sessionId);
    }

    // ==================== Permission ====================

    /**
     * User approves a pending confirmation.
     */
    @PostMapping("/permission/approve")
    public Map<String, String> approvePermission(@RequestParam String token) {
        boolean ok = explicitConfirmGate.resolveConfirmation(token, true);
        return Map.of("status", ok ? "approved" : "expired");
    }

    /**
     * User denies a pending confirmation.
     */
    @PostMapping("/permission/deny")
    public Map<String, String> denyPermission(@RequestParam String token) {
        explicitConfirmGate.resolveConfirmation(token, false);
        return Map.of("status", "denied");
    }
}
