package kr.go.h2spec.parser.llm.schema;

import java.util.List;

/**
 * 상세기능(오퍼레이션) 하나의 추출 결과.
 *
 * @param operationName 정보 표에 적힌 영문 오퍼레이션명 그대로 ({@code getMsrstnList}). 없으면 빈 문자열
 * @param title 국문 기능명
 * @param description 기능 설명. 문서 표기 그대로
 * @param urlCandidates 주소 후보. LLM은 고르지 않고 원문 그대로 적는다
 * @param httpMethod 문서에 적히거나 체크된 메서드
 * @param responseFormat 응답 형식
 * @param defaultResponseFormat 포맷 파라미터 없이 부를 때 오는 형식
 * @param formatParameter 응답 형식을 고르는 파라미터 이름 ({@code returnType}, {@code _type} 등). 없으면 빈 문자열
 * @param auth 인증 방식
 * @param requestParameters 요청 파라미터
 * @param responseStructure 응답 경로의 구조를 어디서 정했는지
 * @param responseExampleFormat 응답 경로를 정한 응답 예제의 형식. 예제를 쓰지 않았으면 {@code NONE}.
 *                              기본 형식을 모를 때 정규화가 IR의 응답 형식을 경로와 같은 형식으로 맞추는 데 쓴다
 * @param responseFields 값을 담는 응답 필드. 하위 필드를 감싸기만 하는 컨테이너는 경로로만 나타난다
 * @param resultCode 결과코드 필드와 성공코드 후보
 * @param errorCodes 이 오퍼레이션에만 있는 에러코드 표. 문서 공통 표는 {@link LlmExtraction#errorCodes()}에 적는다
 * @param evidence 근거 블록 번호 ({@code T27}). {@code DocumentSerializer}가 붙인 번호다
 * @param uncertain 확신 없는 점 (한국어 문장)
 * @param unsupported GET이 아닌 메서드, 이름이 serviceKey가 아닌 인증키, 헤더 인증처럼 generator가 지원하지 않는 요소.
 *                    정규화가 {@code [LLM]} 노트로 옮긴다
 */
public record LlmOperation(
        String operationName,
        String title,
        String description,
        LlmUrlCandidates urlCandidates,
        HttpMethod httpMethod,
        ResponseFormat responseFormat,
        DefaultResponseFormat defaultResponseFormat,
        String formatParameter,
        LlmAuth auth,
        List<LlmRequestParameter> requestParameters,
        ResponseStructure responseStructure,
        ResponseExampleFormat responseExampleFormat,
        List<LlmResponseField> responseFields,
        LlmResultCode resultCode,
        List<LlmErrorCode> errorCodes,
        List<String> evidence,
        List<String> uncertain,
        List<String> unsupported) {

    /**
     * 적히거나 체크된 메서드에 GET이 있으면 {@code GET}이다. GET 없이 다른 메서드만 밝혔으면 {@code POST}·{@code OTHER},
     * 아무 표기도 없으면 {@code UNKNOWN}이다. 메서드를 적은 문서가 드물어 정규화는 {@code UNKNOWN}도 GET으로 둔다.
     */
    public enum HttpMethod { GET, POST, OTHER, UNKNOWN }

    /** 응답 형식. 두 형식을 모두 내면 {@code BOTH}다. */
    public enum ResponseFormat { XML, JSON, BOTH, UNKNOWN }

    /** 포맷 파라미터 없이 부를 때 오는 형식. 한 번에 한 형식만 오므로 {@code BOTH}가 없다. */
    public enum DefaultResponseFormat { XML, JSON, UNKNOWN }

    /** 응답 경로의 구조를 정한 곳. 오퍼레이션 하나에 하나라서, 표준 구조 가정과 예제를 따른 경로가 섞이지 않는다. */
    public enum ResponseStructure {
        /** 응답 예제 */
        EXAMPLE,
        /** 표의 들여쓰기·└ 표시 */
        TABLE,
        /** 알 수 없어 표준 구조({@code response.header}, {@code response.body}, {@code response.body.items.item[]})를 가정함 */
        STANDARD_ASSUMED
    }

    /**
     * 응답 경로를 정한 응답 예제의 형식. XML 예제와 JSON 예제는 같은 응답이라도 구조가 다를 수 있어(JSON의 {@code items} 배열과
     * XML의 {@code <items><item>}), IR의 응답 형식이 경로와 다른 형식이면 생성 DTO가 응답을 읽지 못할 수 있다.
     */
    public enum ResponseExampleFormat {
        XML,
        JSON,
        /** 응답 예제를 쓰지 않았다 (표의 들여쓰기로 정했거나 표준 구조를 가정함) */
        NONE
    }
}
