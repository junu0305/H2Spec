package kr.go.h2spec.parser.llm.schema;

/**
 * 문서의 에러코드 표의 행 하나.
 *
 * @param code 에러코드
 * @param message 에러 메시지
 */
public record LlmErrorCode(String code, String message) {
}
