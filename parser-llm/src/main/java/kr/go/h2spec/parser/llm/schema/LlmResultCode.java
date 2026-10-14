package kr.go.h2spec.parser.llm.schema;

import java.util.List;

/**
 * 결과코드 필드와 성공코드 후보. 표와 예제가 자주 엇갈려 LLM은 성공코드를 고르지 않고,
 * 후보를 출처와 함께 모두 받아 코드가 정책대로 고른다.
 *
 * @param fieldName 결과코드 필드 이름. 없으면 빈 문자열
 * @param successCodes 정상 코드를 찾은 자리마다 하나씩. 값이 서로 달라도 모두 담는다
 */
public record LlmResultCode(String fieldName, List<LlmSuccessCode> successCodes) {
}
