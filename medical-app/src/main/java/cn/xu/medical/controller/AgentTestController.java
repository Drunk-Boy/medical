package cn.xu.medical.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentTestController {

    private final ChatModel chatModel;

    public AgentTestController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Test endpoint — verify DeepSeek connectivity (sync)
     */
    @GetMapping("/test")
    public Map<String, String> test(@RequestParam(defaultValue = "Hello, reply with 'OK'") String message) {
        String response = ChatClient.create(chatModel)
            .prompt()
            .user(message)
            .call()
            .content();
        return Map.of("status", "ok", "response", response);
    }

    /**
     * Test endpoint — verify streaming
     */
    @GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> testStream(@RequestParam(defaultValue = "Count from 1 to 5") String message) {
        return ChatClient.create(chatModel)
            .prompt()
            .user(message)
            .stream()
            .content();
    }
}
