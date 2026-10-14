package kr.go.h2spec.parser.llm.client;

import java.util.Objects;

/**
 * 회사에 보낼 요청. 앞의 세 부분은 프롬프트에 놓이는 순서 그대로다.
 * <p>
 * 바뀌지 않는 시스템 프롬프트와 문서를 앞에, 같은 문서를 다시 부를 때 바뀌는 지시를 뒤에 둔다.
 * 앞부분이 그대로 남아야 회사별 프롬프트 캐싱이 걸리기 때문이다.
 *
 * @param systemPrompt 시스템 프롬프트. 고정이고 캐시되는 앞부분이다
 * @param document 직렬화한 문서({@link kr.go.h2spec.parser.llm.DocumentSerializer}). 같은 문서를 다시 부를 때도 그대로인 앞부분이다
 * @param instruction 작업 지시. 나눠 부를 때는 범위가 바뀌고, 검증 결과를 알려 다시 부를 때는 뒤에 글이 붙는다
 * @param jsonSchema 출력이 따를 중간 스키마(JSON Schema) 글
 * @param maxOutputTokens 출력 토큰 상한. thinking 토큰도 이 안에서 쓰인다
 * @param cacheDocument 문서도 회사의 프롬프트 캐시에 올릴지. 같은 문서를 여러 번 부르는 나눠 부르기에서만 켠다. 결과를 바꾸지 않아
 *                      결과 캐시 키에는 넣지 않는다
 */
public record LlmRequest(
        String systemPrompt,
        String document,
        String instruction,
        String jsonSchema,
        int maxOutputTokens,
        boolean cacheDocument) {

    public LlmRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("출력 토큰 상한은 1 이상이어야 합니다: " + maxOutputTokens);
        }
    }

    /** 문서는 프롬프트 캐시에 올리지 않는 요청. 문서를 한 번 부를 때다 */
    public LlmRequest(String systemPrompt, String document, String instruction, String jsonSchema, int maxOutputTokens) {
        this(systemPrompt, document, instruction, jsonSchema, maxOutputTokens, false);
    }
}
