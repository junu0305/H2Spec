package kr.go.h2spec.parser.llm.gemini;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.StreamingHttp;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Gemini API({@code streamGenerateContent}) 어댑터. 회사 SDK 없이 JDK HTTP 클라이언트와 이미 쓰는 Jackson으로 부른다.
 * <p>
 * 연결·스트리밍·재시도·키 가리기는 {@link StreamingHttp}가 하고, 여기서는 Gemini의 주소와 요청 머리·본문({@link GeminiRequests}),
 * 응답 해석({@link GeminiResponses})만 정한다. API 키는 주소의 {@code key=}가 아니라 요청 머리({@code x-goog-api-key})에 실어
 * 로그나 오류 글에 주소가 찍혀도 키가 남지 않게 한다.
 */
public final class GeminiClient implements LlmClient {

    /** 기본 API 주소. {@link LlmSettings#baseUrl()}로 바꾼다 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://generativelanguage.googleapis.com");
    /** 모델 ID는 주소의 경로가 되므로 경로를 바꿀 수 있는 글자를 받지 않는다 */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String MODELS_PREFIX = "models/";

    private final LlmSettings settings;
    private final StreamingHttp http;
    private final URI streamUri;

    /**
     * @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소
     * @throws IllegalArgumentException 모델 ID에 주소에 쓸 수 없는 글자가 있을 때
     */
    public GeminiClient(LlmSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.streamUri = streamUri(settings);
        this.http = new StreamingHttp("Gemini API", settings);
    }

    /**
     * @throws IllegalArgumentException 모델 ID에 주소에 쓸 수 없는 글자가 있을 때
     */
    static URI streamUri(LlmSettings settings) {
        // Gemini 문서와 모델 목록은 모델을 models/gemini-… 꼴로도 적는다. 주소에는 models/ 뒤의 ID만 넣는다
        String model = settings.model().startsWith(MODELS_PREFIX)
                ? settings.model().substring(MODELS_PREFIX.length())
                : settings.model();
        if (!MODEL_ID.matcher(model).matches()) {
            throw new IllegalArgumentException("Gemini 모델 ID는 영문자·숫자·.·_·-만 쓸 수 있습니다: " + settings.model());
        }
        return StreamingHttp.resolve(settings.baseUrl() == null ? DEFAULT_BASE_URL : settings.baseUrl(),
                "/v1beta/models/" + model + ":streamGenerateContent?alt=sse");
    }

    /**
     * @throws IllegalArgumentException 요청의 스키마가 JSON 객체가 아닐 때. 보내기 전에 알린다
     */
    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        return http.send(http.post(streamUri, GeminiRequests.body(request, settings))
                        .header("x-goog-api-key", settings.apiKey())
                        .build(),
                GeminiResponses::fromStream, GeminiResponses::fromHttpError);
    }
}
