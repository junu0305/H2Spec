package kr.go.h2spec.parser.llm.client;

import java.util.Objects;

/**
 * 회사가 돌려준 결과.
 * <p>
 * 회사마다 토큰을 세는 방식이 달라 어댑터가 {@link Usage}의 뜻에 맞춰 옮긴다.
 * 그래야 비용 추정과 CLI 출력이 회사와 상관없이 같은 계산을 한다.
 *
 * @param json 모델이 낸 글. {@link Finish#COMPLETE}면 스키마를 따르는 JSON이고, 잘림·거절이면 끊긴 글이나 빈 글일 수 있다
 * @param model 응답한 모델 ID. 회사가 알려 준 값이다
 * @param usage 토큰 사용량
 * @param finish 출력이 끝난 방식
 */
public record LlmResponse(String json, String model, Usage usage, Finish finish) {

    public LlmResponse {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(finish, "finish");
    }

    /**
     * 토큰 사용량. 입력 세 항목을 더하면 보낸 입력 전체다.
     *
     * @param inputTokens 캐시에서 읽지도, 캐시에 쓰지도 않은 입력
     * @param cacheReadInputTokens 캐시에서 읽은 입력
     * @param cacheWriteInputTokens 캐시에 쓴 입력 (Claude는 따로 과금한다)
     * @param outputTokens 출력 단가로 과금되는 전부 (thinking 포함)
     * @param reasoningTokens outputTokens 중 thinking. 회사가 알려 줄 때만 채우고, 아니면 0이다
     */
    public record Usage(
            long inputTokens,
            long cacheReadInputTokens,
            long cacheWriteInputTokens,
            long outputTokens,
            long reasoningTokens) {
    }

    /** 출력이 끝난 방식 */
    public enum Finish {
        /** 모델이 출력을 끝냈다 */
        COMPLETE,
        /** 출력 한도에 걸려 출력이 끊겼다. llm 엔진은 다 적힌 오퍼레이션을 건져 그 뒤부터 나눠 불러 이어 간다 */
        TRUNCATED,
        /** 회사가 요청을 거절했다 */
        REFUSED
    }
}
