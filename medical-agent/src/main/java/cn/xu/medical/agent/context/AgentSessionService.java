package cn.xu.medical.agent.context;

import cn.xu.medical.agent.common.dto.AgentEvent;
import cn.xu.medical.agent.common.entity.AgentMessage;
import cn.xu.medical.agent.common.entity.AgentSession;
import cn.xu.medical.agent.common.mapper.AgentMessageMapper;
import cn.xu.medical.agent.common.mapper.AgentSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manages agent session lifecycle: create, list, load history, delete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;

    public AgentSession create(String title, String projectDir, String modelName) {
        AgentSession session = AgentSession.builder()
            .title(title != null ? title : "New Session")
            .projectDir(projectDir)
            .modelName(modelName != null ? modelName : "deepseek-chat")
            .status("ACTIVE")
            .build();
        sessionMapper.insert(session);
        log.info("Created session: {} ({})", session.getId(), session.getTitle());
        return session;
    }

    public Optional<AgentSession> getById(String sessionId) {
        return Optional.ofNullable(sessionMapper.selectById(sessionId));
    }

    public List<AgentSession> listActive() {
        return sessionMapper.selectList(
            new LambdaQueryWrapper<AgentSession>()
                .eq(AgentSession::getStatus, "ACTIVE")
                .orderByDesc(AgentSession::getUpdatedAt)
        );
    }

    public void archive(String sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setStatus("ARCHIVED");
            sessionMapper.updateById(session);
        }
    }

    public void delete(String sessionId) {
        sessionMapper.deleteById(sessionId);
        // Messages cascade-deleted by FK
    }

    /**
     * Load recent messages for context.
     */
    public List<AgentMessage> loadRecentMessages(String sessionId, int limit) {
        return messageMapper.selectList(
            new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByAsc(AgentMessage::getSeq)
                .last("LIMIT " + limit)
        );
    }

    /**
     * Save a message to the session.
     */
    public AgentMessage saveMessage(String sessionId, String role, String content, String metadataJson, int seq) {
        AgentMessage msg = AgentMessage.builder()
            .sessionId(sessionId)
            .role(role)
            .content(content)
            .metadataJson(metadataJson)
            .tokenCount(estimateTokens(content))
            .seq(seq)
            .build();
        messageMapper.insert(msg);

        // Touch session updated_at
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        return msg;
    }

    /**
     * Get the next sequence number for a session.
     */
    public int getNextSeq(String sessionId) {
        Long count = messageMapper.selectCount(
            new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
        );
        return count.intValue() + 1;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int chinese = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chinese++;
            }
        }
        int other = text.length() - chinese;
        return (int)(chinese * 1.5 + other / 4.0);
    }
}
