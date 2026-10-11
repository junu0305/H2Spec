package kr.go.h2spec.parser.llm.schema;

/**
 * 인증 방식.
 *
 * @param kind 인증키를 받는 방식
 * @param parameterName 인증키 파라미터 이름 ({@code serviceKey}, {@code apiKey}, {@code your_appkey} 등). 없으면 빈 문자열
 */
public record LlmAuth(Kind kind, String parameterName) {

    /**
     * 인증키를 받는 방식. 이름이 serviceKey가 아닌 쿼리 키({@code apiKey}, {@code authApiKey})도
     * {@code SERVICE_KEY_QUERY}이고, 이름은 {@code parameterName}으로 가른다.
     */
    public enum Kind { SERVICE_KEY_QUERY, PATH_KEY, HEADER_KEY, NONE, OTHER, UNKNOWN }
}
