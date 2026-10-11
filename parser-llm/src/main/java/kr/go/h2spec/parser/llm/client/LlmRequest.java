package kr.go.h2spec.parser.llm.client;

import java.util.Objects;

/**
 * 회사에 보낼 요청 (설계 6.1). 앞의 세 부분은 프롬프트에 놓이는 순서 그대로다.
 * <p>
 * 바뀌지 않는 시스템 프롬프트와 문서를 앞에, 오퍼레이션별 호출(설계 4.4)에서 바뀌는 지시를 뒤에 둔다.
 * 앞부분이 그대로 남아야 회사별 프롬프트 캐싱이 걸리기 때문이다(설계 4.3).
 *
 * @param systemPrompt 시스템 프롬프트. 고정이고 캐시되는 앞부분이다
 * @param document 직렬화한 문서({@link kr.go.h2spec.parser.llm.DocumentSerializer}). 캐시되는 앞부분이다
 * @param instruction 작업 지시. 오퍼레이션별 호출에서만 바뀐다
 * @param jsonSchema 출력이 따를 중간 스키마(JSON Schema) 글
 * @param maxOutputTokens 출력 토큰 상한. thinking 토큰도 이 안에서 쓰인다(설계 4.4)
 */
public record LlmRequest(
        String systemPrompt,
        String document,
        String instruction,
        String jsonSchema,
        int maxOutputTokens) {

    public LlmRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("출력 토큰 상한은 1 이상이어야 합니다: " + maxOutputTokens);
        }
    }
}
