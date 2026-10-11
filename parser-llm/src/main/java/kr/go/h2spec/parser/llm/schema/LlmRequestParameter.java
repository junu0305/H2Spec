package kr.go.h2spec.parser.llm.schema;

/**
 * 요청 파라미터.
 *
 * @param name 파라미터 이름. 문서 표기 그대로
 * @param location 파라미터 위치. 주소의 {@code {…}} 자리 변수는 {@code PATH}다
 * @param type 값의 타입
 * @param required 필수 여부
 * @param description 설명. 설명 칸이 비어 있으면 국문 항목명
 * @param example 예시 값. 없으면 빈 문자열
 * @param defaultValue 기본값. IR 키는 {@code default}다. 없으면 빈 문자열
 * @param common 공통 구간에서 온 항목이면 true
 */
public record LlmRequestParameter(
        String name,
        Location location,
        LlmValueType type,
        Requirement required,
        String description,
        String example,
        String defaultValue,
        boolean common) {

    /** 파라미터 위치. generator는 {@code QUERY}·{@code PATH}만 받는다. */
    public enum Location { QUERY, PATH, HEADER, BODY }

    /** 필수 표기(1, Y, 필수, ○)는 {@code REQUIRED}, 선택 표기(0, N, 옵션)는 {@code OPTIONAL}, 표기가 없으면 {@code UNKNOWN}이다. */
    public enum Requirement { REQUIRED, OPTIONAL, UNKNOWN }
}
