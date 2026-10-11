package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.llm.schema.LlmAuth;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Location;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Requirement;
import kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.schema.LlmValueType;

import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.error;
import static kr.go.h2spec.parser.llm.LlmFixtures.field;
import static kr.go.h2spec.parser.llm.LlmFixtures.operation;
import static kr.go.h2spec.parser.llm.LlmFixtures.parameter;
import static kr.go.h2spec.parser.llm.LlmFixtures.success;

/**
 * 규칙마다의 테스트가 쓰는 작은 표준 기술문서. 실제 에어코리아 측정소정보 문서의 첫 오퍼레이션을 줄여 만들었다.
 * <pre>
 * [P1] 서비스 개요            [T2] 서비스 URL
 * [P3] 상세기능정보          [T4] 국문명·영문명·설명·Call Back URL
 * [P5] 요청 메시지 명세      [T6] serviceKey, returnType, numOfRows, pageNo, stationName, areaCode
 * [P7] 응답 메시지 명세      [T8] resultCode, resultMsg, totalCount, stationName, sidoName, stationCode
 * [P9] 요청/응답 메시지 예제 [T10] 요청 주소와 XML 응답 (stationCode는 표에만 있다)
 * [P11] 에러 코드            [T12] 00 정상, 01, 30
 * </pre>
 */
public final class SampleDocument {

    public static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
    public static final String SERVICE = "http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc";
    public static final String URL = SERVICE + "/getMsrstnList";
    public static final String EXAMPLE_URL = URL + "?serviceKey=키&pageNo=1&numOfRows=10";
    public static final String RESPONSE_EXAMPLE = """
            <response>
            <header>
            <resultCode>00</resultCode>
            <resultMsg>NORMAL SERVICE</resultMsg>
            </header>
            <body>
            <items>
            <item>
            <stationName>종로구</stationName>
            <sidoName>서울</sidoName>
            </item>
            </items>
            <totalCount>1</totalCount>
            </body>
            </response>""";

    private SampleDocument() {
    }

    public static List<Block> standard() {
        return List.of(
                heading("서비스 개요"),
                table(row("서비스명(영문)", "MsrstnInfoInqireSvc"), row("서비스 URL", SERVICE)),
                heading("상세기능정보"),
                table(row("상세기능명(국문)", "측정소 목록 조회"), row("오퍼레이션명(영문)", "getMsrstnList"),
                        row("상세기능 설명", "측정소 목록을 조회하는 기능"), row("Call Back URL", URL)),
                heading("요청 메시지 명세"),
                table(HEADER,
                        row("serviceKey", "서비스키", "100", "1", "인증키", "공공데이터포털에서 받은 인증키"),
                        row("returnType", "데이터표출방식", "4", "0", "xml", "xml 또는 json"),
                        row("numOfRows", "한 페이지 결과 수", "4", "0", "10", "한 페이지 결과 수"),
                        row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호"),
                        row("stationName", "측정소명", "30", "0", "종로구", "측정소명"),
                        row("areaCode", "지역코드", "4", "0", "0311", "지역 코드")),
                heading("응답 메시지 명세"),
                table(HEADER,
                        row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("resultMsg", "결과메시지", "50", "1", "NORMAL SERVICE", "결과메시지"),
                        row("totalCount", "전체 결과 수", "4", "1", "1", "전체 결과 수"),
                        row("stationName", "측정소명", "30", "1", "종로구", "측정소명"),
                        row("sidoName", "시도명", "20", "1", "서울", "시도명"),
                        row("stationCode", "측정소 코드", "6", "1", "011123", "측정소 코드")),
                heading("요청/응답 메시지 예제"),
                table(row("요청메시지"), row(EXAMPLE_URL), row("응답메시지"), row(RESPONSE_EXAMPLE)),
                heading("에러 코드"),
                table(row("에러코드", "에러메시지", "설명"),
                        row("00", "NORMAL SERVICE", "정상"),
                        row("01", "APPLICATION ERROR", "어플리케이션 에러"),
                        row("30", "SERVICE KEY IS NOT REGISTERED ERROR", "등록되지 않은 서비스키")));
    }

    /** {@link #standard()} 문서를 그대로 옮긴 LLM 출력 */
    public static LlmFixtures.OperationBuilder standardOperation() {
        return operation()
                .name("getMsrstnList").title("측정소 목록 조회").description("측정소 목록을 조회하는 기능")
                .callBackUrl(URL).requestExampleUrl(EXAMPLE_URL).serviceUrl(SERVICE)
                .format(LlmOperation.ResponseFormat.BOTH, LlmOperation.DefaultResponseFormat.XML, "returnType")
                .auth(LlmAuth.Kind.SERVICE_KEY_QUERY, "serviceKey")
                .parameters(
                        parameter("serviceKey", Location.QUERY, LlmValueType.STRING, Requirement.REQUIRED, "인증키", "", false),
                        parameter("returnType", Location.QUERY, LlmValueType.STRING, Requirement.OPTIONAL, "xml", "", false),
                        parameter("numOfRows", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "10", "", false),
                        parameter("pageNo", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "1", "", false),
                        parameter("stationName", Location.QUERY, LlmValueType.STRING, Requirement.OPTIONAL, "종로구", "", false))
                .structure(LlmOperation.ResponseStructure.EXAMPLE)
                .fields(
                        field("response.header.resultCode", LlmValueType.STRING, "00", PathSource.RESPONSE_EXAMPLE, false),
                        field("response.header.resultMsg", LlmValueType.STRING, "NORMAL SERVICE", PathSource.RESPONSE_EXAMPLE, false),
                        field("response.body.totalCount", LlmValueType.INTEGER, "1", PathSource.RESPONSE_EXAMPLE, false),
                        field("response.body.items.item[].stationName", LlmValueType.STRING, "종로구", PathSource.RESPONSE_EXAMPLE, false),
                        field("response.body.items.item[].sidoName", LlmValueType.STRING, "서울", PathSource.RESPONSE_EXAMPLE, false),
                        field("response.body.items.item[].stationCode", LlmValueType.STRING, "011123", PathSource.FIELD_TABLE, false))
                .resultCode("resultCode", success("00", LlmSuccessCode.Source.FIELD_TABLE),
                        success("00", LlmSuccessCode.Source.RESPONSE_EXAMPLE))
                .errors(error("01", "APPLICATION ERROR"), error("30", "SERVICE KEY IS NOT REGISTERED ERROR"))
                .evidence("T4", "T6", "T8", "T10");
    }

    public static Block.Heading heading(String text) {
        return new Block.Heading(text);
    }

    @SafeVarargs
    public static Block.Table table(List<String>... rows) {
        return new Block.Table(List.of(rows));
    }

    public static List<String> row(String... cells) {
        return List.of(cells);
    }
}
