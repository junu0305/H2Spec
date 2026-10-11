package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Claude 스트리밍 응답 본문(SSE)을 실제 응답과 같은 순서와 모양으로 만든다.
 * 이벤트의 data는 Jackson으로 써서 글 안의 따옴표·줄바꿈이 실제처럼 escape된다.
 */
final class ClaudeSse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder sse = new StringBuilder();

    private ClaudeSse() {
    }

    /** message_start. 사용량의 output_tokens는 실제처럼 처음에는 작은 값이다 */
    static ClaudeSse start(String model, long inputTokens, long cacheWriteInputTokens, long cacheReadInputTokens) {
        ObjectNode message = MAPPER.createObjectNode()
                .put("id", "msg_01TEST")
                .put("type", "message")
                .put("role", "assistant")
                .put("model", model);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        message.putObject("usage")
                .put("input_tokens", inputTokens)
                .put("cache_creation_input_tokens", cacheWriteInputTokens)
                .put("cache_read_input_tokens", cacheReadInputTokens)
                .put("output_tokens", 1);
        return new ClaudeSse().event("message_start", MAPPER.createObjectNode().put("type", "message_start")
                .set("message", message));
    }

    ClaudeSse ping() {
        return event("ping", MAPPER.createObjectNode().put("type", "ping"));
    }

    /** thinking 블록. 생각 글과 서명이 delta로 온다 */
    ClaudeSse thinking(int index, String thinking) {
        ObjectNode block = MAPPER.createObjectNode().put("type", "thinking").put("thinking", "").put("signature", "");
        blockStart(index, block);
        delta(index, MAPPER.createObjectNode().put("type", "thinking_delta").put("thinking", thinking));
        delta(index, MAPPER.createObjectNode().put("type", "signature_delta").put("signature", "EqQBCgIYAhIM1gbcDa9GJwZA"));
        return blockStop(index);
    }

    /** 글 블록. 조각마다 text_delta 하나다 */
    ClaudeSse text(int index, String... chunks) {
        blockStart(index, MAPPER.createObjectNode().put("type", "text").put("text", ""));
        for (String chunk : chunks) {
            delta(index, MAPPER.createObjectNode().put("type", "text_delta").put("text", chunk));
        }
        return blockStop(index);
    }

    /** message_delta와 message_stop. message_delta의 output_tokens는 누계다 */
    ClaudeSse stop(String stopReason, long outputTokens) {
        return messageDelta(stopReason, MAPPER.createObjectNode().put("output_tokens", outputTokens)).messageStop();
    }

    /** message_delta만 더한다. 사용량 항목을 직접 정할 때 쓴다 */
    ClaudeSse messageDelta(String stopReason, ObjectNode usage) {
        ObjectNode data = MAPPER.createObjectNode().put("type", "message_delta");
        data.putObject("delta").put("stop_reason", stopReason).putNull("stop_sequence");
        data.set("usage", usage);
        return event("message_delta", data);
    }

    ClaudeSse messageStop() {
        return event("message_stop", MAPPER.createObjectNode().put("type", "message_stop"));
    }

    /** 스트림 중간의 오류 이벤트 */
    ClaudeSse error(String type, String message) {
        ObjectNode data = MAPPER.createObjectNode().put("type", "error");
        data.putObject("error").put("type", type).put("message", message);
        return event("error", data);
    }

    /** 이름과 data를 그대로 적은 이벤트 */
    ClaudeSse raw(String name, String data) {
        sse.append("event: ").append(name).append('\n').append("data: ").append(data).append("\n\n");
        return this;
    }

    String build() {
        return sse.toString();
    }

    private void blockStart(int index, ObjectNode block) {
        event("content_block_start", MAPPER.createObjectNode().put("type", "content_block_start").put("index", index)
                .set("content_block", block));
    }

    private void delta(int index, ObjectNode delta) {
        event("content_block_delta", MAPPER.createObjectNode().put("type", "content_block_delta").put("index", index)
                .set("delta", delta));
    }

    private ClaudeSse blockStop(int index) {
        return event("content_block_stop", MAPPER.createObjectNode().put("type", "content_block_stop").put("index", index));
    }

    private ClaudeSse event(String name, ObjectNode data) {
        return raw(name, data.toString());
    }
}
