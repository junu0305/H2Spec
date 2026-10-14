package kr.go.h2spec.parser.llm.schema;

/**
 * 문서에서 찾은 성공코드 후보 하나.
 *
 * @param code 문서 표기 그대로의 코드 ({@code 00}, {@code 0}, {@code INFO-00})
 * @param source 코드를 찾은 곳
 */
public record LlmSuccessCode(String code, Source source) {

    /** 성공코드를 찾은 곳. 후보가 여럿이면 응답 예제, 응답 필드 표, 에러코드 표의 정상 행 순으로 고른다. */
    public enum Source {
        /** 응답 필드 표의 샘플·설명 */
        FIELD_TABLE,
        /** 응답 예제의 결과코드 값 */
        RESPONSE_EXAMPLE,
        /** 에러코드 표의 정상 행 */
        ERROR_TABLE
    }
}
