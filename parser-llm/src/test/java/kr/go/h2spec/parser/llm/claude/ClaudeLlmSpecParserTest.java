package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.llm.DocumentSerializer;
import kr.go.h2spec.parser.llm.ExtractionCache;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.TestDocuments;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * llm 엔진을 Claude 어댑터와 이어 가짜 서버로 끝까지 부른다. 루프백 주소의 서버라 실제 네트워크로는 나가지 않는다.
 * 조립한 엔진이 회사에 실제로 보내는 요청과, 어댑터가 바꾼 응답·오류가 엔진의 결과·실패 종류로 이어지는지 본다.
 */
@Timeout(30)
class ClaudeLlmSpecParserTest {

    private static final String API_KEY = "sk-ant-api03-SECRET-test-key_0123456789";
    private static final String MODEL = "claude-opus-5-5";
    private static final String DOCUMENT = "msrstn-info.docx";

    private final List<String> warnings = new ArrayList<>();
    private FakeClaudeServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeClaudeServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void 스트리밍으로_받은_녹화_응답을_IR로_바꾸고_보낸_요청과_사용량을_알린다() throws Exception {
        String recording = TestDocuments.recording(TestDocuments.PROVIDER, TestDocuments.recordingName(DOCUMENT));
        int half = recording.length() / 2;
        server.reply(FakeClaudeServer.sse(ClaudeSse.start(MODEL, 120, 2500, 0)
                .ping()
                .thinking(0, "")
                .text(1, recording.substring(0, half), recording.substring(half))
                .stop("end_turn", 456)
                .build()));

        SpecParseResult result = parser().parse(TestDocuments.path(DOCUMENT));

        assertEquals(3, result.apis().size());
        assertEquals(new SpecParseResult.Engine("llm", "claude", MODEL, "medium", false,
                new SpecParseResult.TokenUsage(120, 0, 2500, 456)), result.engine());
        ExtractionPrompt prompt = ExtractionPrompt.load();
        JsonNode body = server.received().get(0).json();
        assertEquals(prompt.promptText(), body.get("system").asText());
        JsonNode content = body.get("messages").get(0).get("content");
        assertEquals("<document>\n" + new DocumentSerializer().serialize(TestDocuments.read(DOCUMENT)) + "</document>",
                content.get(0).get("text").asText());
        assertEquals(prompt.wholeDocumentInstruction(), content.get(1).get("text").asText());
        assertEquals(LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS, body.get("max_tokens").asInt());
        assertEquals("medium", body.get("output_config").get("effort").asText());
        assertEquals(List.of(), warnings, "녹화는 세 오퍼레이션을 모두 담아 rule 엔진만 찾은 것이 없다");
    }

    @Test
    void 키_오류는_CLI가_전체를_멈추는_AUTH로_알리고_어디에도_키를_남기지_않는다() {
        server.reply(FakeClaudeServer.error(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key: "
                        + API_KEY + "\"},\"request_id\":\"req_test\"}"));

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser().parse(TestDocuments.path(DOCUMENT)));

        assertEquals(SpecParseException.Kind.AUTH, e.kind());
        assertEquals("LLM API 키 오류로 변환하지 못했습니다 (msrstn-info.docx, claude): Claude API 오류 (HTTP 401): "
                + "authentication_error: invalid x-api-key: **** (request_id req_test)", e.getMessage());
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(API_KEY), trace.toString());
        assertEquals(1, server.received().size());
    }

    @Test
    void 출력_한도에_걸린_스트림은_그_문서의_실패로_알린다() {
        server.reply(FakeClaudeServer.sse(ClaudeSse.start(MODEL, 120, 0, 0)
                .text(0, "{\"serviceName\": \"에어코리아")
                .stop("max_tokens", 64_000)
                .build()));

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser().parse(TestDocuments.path(DOCUMENT)));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertTrue(e.getMessage().startsWith("LLM 출력이 출력 한도 64000 토큰에 걸려 잘렸습니다 (msrstn-info.docx, 출력 64000 토큰"),
                e.getMessage());
    }

    private LlmSpecParser parser() {
        // 재시도 대기를 실제로 기다리지 않도록 재시도는 끈다. 재시도는 ClaudeClientTest가 본다
        LlmSettings settings = LlmSettings.of(MODEL, "medium", API_KEY).withBaseUrl(server.baseUrl()).withRetryAttempts(0);
        return LlmSpecParser.of(new ClaudeProviderFactory(), settings,
                LlmSpecParser.Options.defaults().withCache(ExtractionCache.disabled()).withWarnings(warnings::add));
    }
}
