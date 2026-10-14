package kr.go.h2spec.parser.llm.schema;

/**
 * 오퍼레이션 주소 후보. LLM은 주소를 고르지 않고 칸마다 원문 그대로 옮기며, 주소 안에 공백을 넣지 않는다.
 * 코드가 rule 엔진과 같은 규칙으로 고르므로 같은 후보에서는 엔진이 달라도 같은 주소가 나온다.
 * 칸의 몫은 rule 엔진과 같게 나눈다. 없으면 빈 문자열이다.
 *
 * @param callBackUrl 정보 표의 주소 칸 (Call Back URL, 요청주소, 요청 URL, 호출 URL, End Point(URL), API URL 등)
 * @param tableUrl 요청 표 안의 주소 행 (END POINT URL 등)
 * @param urlLine "요청 URL :"처럼 이름표가 붙은 문단의 주소
 * @param requestExampleUrl "요청 예제"·"요청 메시지 예제" 같은 예제 구간의 첫 주소
 * @param serviceUrl 이 오퍼레이션이 속한 서비스 개요 표의 서비스 URL. 개발·운영으로 나뉘면 운영.
 *                   한 문서에 서비스가 여럿일 수 있어 오퍼레이션마다 적는다
 */
public record LlmUrlCandidates(
        String callBackUrl,
        String tableUrl,
        String urlLine,
        String requestExampleUrl,
        String serviceUrl) {
}
