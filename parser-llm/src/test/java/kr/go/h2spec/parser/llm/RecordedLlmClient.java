package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 녹화한 LLM 응답을 재생하는 클라이언트. 네트워크를 쓰지 않고 키도 없다.
 * <p>
 * 녹화({@code llm-recordings/<회사>/<문서>.json})는 회사와 무관한 중간 스키마 JSON이라, 어느 회사에서 녹화했든 같은 하류 코드
 * (검증 → 정규화 → 교차검증 → IrLoader → CodeGenerator)를 시험한다. 회사마다 다른 응답 해석(어댑터)은 거치지 않는다.
 * 녹화는 그 문서를 직렬화한 글을 보낼 때만 재생한다. 다른 문서에 녹화가 잘못 붙으면 실제 호출과 다른 것을 시험하게 되기 때문이다.
 */
final class RecordedLlmClient implements LlmClient {

    /** 재생한 응답의 모델. 녹화는 중간 스키마 JSON뿐이라 모델이 없어 기본 모델로 둔다 */
    static final String MODEL = "claude-sonnet-5-5";
    /** 재생한 응답의 사용량. 녹화에는 사용량이 없어, 엔진 정보의 합계를 확인할 수 있게 정해 둔 값이다 */
    static final LlmResponse.Usage USAGE = new LlmResponse.Usage(12_000, 3_000, 2_500, 4_200, 0);

    private final String document;
    private final String serialized;
    private final String json;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    private RecordedLlmClient(String document, String serialized, String json) {
        this.document = document;
        this.serialized = serialized;
        this.json = json;
    }

    /**
     * @param provider 녹화한 회사 ({@link TestDocuments#PROVIDER})
     * @param document 문서 이름 ({@code msrstn-info.docx})
     * @return 그 문서의 녹화를 재생하는 클라이언트
     */
    static RecordedLlmClient of(String provider, String document) {
        return new RecordedLlmClient(document, new DocumentSerializer().serialize(TestDocuments.read(document)),
                TestDocuments.recording(provider, TestDocuments.recordingName(document)));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (!serialized.equals(request.document())) {
            throw new IllegalStateException("녹화한 문서(" + document + ")와 다른 문서를 보냈습니다");
        }
        return new LlmResponse(json, MODEL, USAGE, LlmResponse.Finish.COMPLETE);
    }

    /** @return 받은 요청 (받은 순서) */
    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }
}
