package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 정해 둔 응답이나 실패를 차례로 돌려주는 클라이언트. 실패 처리·JSON 재시도·결과 캐시 테스트가 몇 번 불렸는지 셀 수 있게 한다.
 * 정해 둔 것보다 많이 부르면 실패한다. 테스트가 모르는 호출(과금)이 숨지 않게 하기 위해서다.
 */
final class ScriptedLlmClient implements LlmClient {

    static final String MODEL = "claude-opus-5-5";
    static final LlmResponse.Usage USAGE = new LlmResponse.Usage(1_000, 200, 300, 4_000, 0);

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    /** 다음 호출들에 차례로 돌려줄 응답을 더한다 */
    ScriptedLlmClient reply(LlmResponse... responses) {
        script.addAll(List.of(responses));
        return this;
    }

    /** 다음 호출에 던질 실패를 더한다 */
    ScriptedLlmClient fail(LlmException failure) {
        script.add(failure);
        return this;
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) throws LlmException {
        requests.add(request);
        Object next = script.poll();
        if (next == null) {
            throw new IllegalStateException("정해 둔 응답보다 많이 불렀습니다: " + requests.size() + "번째 호출");
        }
        if (next instanceof LlmException failure) {
            throw failure;
        }
        return (LlmResponse) next;
    }

    /** @return 부른 횟수 */
    int calls() {
        return requests.size();
    }

    /** @return 받은 요청 (받은 순서) */
    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    /** 모델이 출력을 끝낸 응답 */
    static LlmResponse complete(String json) {
        return new LlmResponse(json, MODEL, USAGE, LlmResponse.Finish.COMPLETE);
    }

    /** 출력 한도에 걸려 끊긴 응답 */
    static LlmResponse truncated(long outputTokens) {
        return new LlmResponse("{\"serviceName\": \"대기", MODEL,
                new LlmResponse.Usage(USAGE.inputTokens(), 0, 0, outputTokens, 0), LlmResponse.Finish.TRUNCATED);
    }

}
