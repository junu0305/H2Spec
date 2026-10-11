package kr.go.h2spec.parser.llm.schema;

/**
 * 값을 담는 응답 필드.
 *
 * @param path 실제 응답 구조를 따른 경로. 첫 칸은 응답의 루트이고 반복되는 요소에는 {@code []}를 붙인다
 *             ({@code response.body.items.item[].stationName}, {@code data[].baseDate})
 * @param type 값의 타입
 * @param description 설명. 설명 칸이 비어 있으면 국문 항목명
 * @param sample 샘플 값. 숫자 타입의 앞자리 0 검사에만 쓴다
 * @param pathSource 경로의 마지막 칸, 곧 필드 이름을 읽은 곳
 * @param common 공통 구간에서 온 항목이면 true
 */
public record LlmResponseField(
        String path,
        LlmValueType type,
        String description,
        String sample,
        PathSource pathSource,
        boolean common) {

    /**
     * 필드 이름을 읽은 곳. 표에만 있는 필드는 {@code FIELD_TABLE}이고, 그 앞의 컨테이너 칸은 예제 구조를 따른다.
     * 검증은 마지막 칸을 이쪽에서, 컨테이너 칸을 {@link LlmOperation#responseStructure()} 쪽에서 확인한다.
     */
    public enum PathSource { FIELD_TABLE, RESPONSE_EXAMPLE }
}
