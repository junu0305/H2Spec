package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.StreamingHttp;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * Claude Messages API 어댑터. 회사 SDK 없이 JDK HTTP 클라이언트와 이미 쓰는 Jackson으로 부른다.
 * <p>
 * 연결·스트리밍·재시도·키 가리기는 {@link StreamingHttp}가 하고, 여기서는 Claude의 요청 머리와 본문({@link ClaudeRequests}),
 * 응답 해석({@link ClaudeResponses})만 정한다. API 키는 요청 머리에만 싣는다.
 */
public final class ClaudeClient implements LlmClient {

    /** 기본 API 주소. {@link LlmSettings#baseUrl()}로 바꾼다 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.anthropic.com");
    /** 요청·응답 형식의 버전 ({@code anthropic-version} 머리) */
    static final String API_VERSION = "2023-06-01";

    private final LlmSettings settings;
    private final StreamingHttp http;
    private final URI messagesUri;

    /** @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소 */
    public ClaudeClient(LlmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = new StreamingHttp("Claude API", settings);
        this.messagesUri = StreamingHttp.resolve(settings.baseUrl() == null ? DEFAULT_BASE_URL : settings.baseUrl(),
                "/v1/messages");
    }

    /**
     * @throws IllegalArgumentException 요청의 스키마가 JSON 객체가 아닐 때. 보내기 전에 알린다
     */
    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        HttpRequest.Builder builder = http.post(messagesUri, ClaudeRequests.body(request, settings))
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", API_VERSION);
        if (settings.workspaceId() != null) {
            // 한 워크스페이스에 묶이지 않은 키는 이 머리가 없으면 400으로 거절된다
            builder.header("anthropic-workspace-id", settings.workspaceId());
        }
        return http.send(builder.build(), ClaudeResponses::fromStream, ClaudeResponses::fromHttpError);
    }
}
