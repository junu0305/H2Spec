package kr.go.h2spec.parser.llm.openai;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.StreamingHttp;

import java.net.URI;
import java.util.Objects;

/**
 * OpenAI Responses API 어댑터. 회사 SDK 없이 JDK HTTP 클라이언트와 이미 쓰는 Jackson으로 부른다.
 * <p>
 * 연결·스트리밍·재시도·키 가리기는 {@link StreamingHttp}가 하고, 여기서는 OpenAI의 요청 머리와 본문({@link OpenAiRequests}),
 * 응답 해석({@link OpenAiResponses})만 정한다. API 키는 요청 머리에만 싣는다.
 */
public final class OpenAiClient implements LlmClient {

    /** 기본 API 주소. {@link LlmSettings#baseUrl()}로 바꾼다 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.openai.com");

    private final LlmSettings settings;
    private final StreamingHttp http;
    private final URI responsesUri;

    /** @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소 */
    public OpenAiClient(LlmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = new StreamingHttp("OpenAI API", settings);
        this.responsesUri = StreamingHttp.resolve(settings.baseUrl() == null ? DEFAULT_BASE_URL : settings.baseUrl(),
                "/v1/responses");
    }

    /**
     * @throws IllegalArgumentException 요청의 스키마가 JSON 객체가 아닐 때. 보내기 전에 알린다
     */
    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        return http.send(http.post(responsesUri, OpenAiRequests.body(request, settings))
                        .header("authorization", "Bearer " + settings.apiKey())
                        .build(),
                OpenAiResponses::fromStream, OpenAiResponses::fromHttpError);
    }
}
