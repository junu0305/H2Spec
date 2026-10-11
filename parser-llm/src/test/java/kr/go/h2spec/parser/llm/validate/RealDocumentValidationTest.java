package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.llm.LlmFixtures.OperationBuilder;
import kr.go.h2spec.parser.llm.TestDocuments;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.extraction;
import static kr.go.h2spec.parser.llm.LlmFixtures.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 기관 문서와 그 문서에서 LLM이 낼 만한 출력(손으로 쓴 픽스처)으로 원문 대조 규칙을 본다.
 * 문서마다 설계 5.1이 겨냥한 서식 하나를 대표한다.
 */
class RealDocumentValidationTest {

    private static final String TRAFFIC_LIGHT = "http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList";

    @Test
    void its_traffic_light_진짜_주소의_앞부분인_요청_예제_후보는_V1이_버린다() {
        // 픽스처의 요청 예제 후보는 원문의 …/trafficLightList4에서 4와 질의 문자열을 빠뜨린 것이다
        ValidatedOperation operation = validate("its-traffic-light.hwp", TestDocuments.extraction("its-traffic-light.json"));

        assertNull(operation.address().sources().requestExampleUrl());
        assertNull(operation.address().candidates().exampleUrl());
        assertEquals(List.of("[검증] 원문에서 찾지 못한 주소 후보를 버림 (requestExampleUrl): " + TRAFFIC_LIGHT),
                operation.notes());
        // 남은 후보(서비스 URL + 영문 오퍼레이션명)로 고른다. rule 엔진과 주소가 달라 교차검증이 알린다(CrossCheckerTest)
        assertEquals(TRAFFIC_LIGHT, operation.address().url());
    }

    @Test
    void its_traffic_light_원문의_요청_예제를_옮기면_rule_엔진과_같은_주소를_고른다() {
        LlmExtraction fixture = TestDocuments.extraction("its-traffic-light.json");
        OperationBuilder faithful = OperationBuilder.from(fixture.operations().get(0)).requestExampleUrl(
                TRAFFIC_LIGHT + "4?serviceKey={인증키(URLEncode)}&page=10&countPerPage=1&type=xml");

        ValidatedOperation operation = validate("its-traffic-light.hwp", extraction(faithful));

        assertEquals(TRAFFIC_LIGHT + "4", operation.address().candidates().exampleUrl(),
                "LLM이 주소 안의 공백을 지웠어도 찾고, 찾은 칸의 원래 글에서 꺼낸다");
        assertEquals(TRAFFIC_LIGHT + "4", operation.address().url(), "오퍼레이션명이 든 요청 예제가 서비스 URL보다 앞선다");
        assertEquals(List.of(), operation.notes());
    }

    @Test
    void 기상청_칸_폭_때문에_줄이_바뀐_요청_예제_주소도_V1을_통과한다() {
        // 원문 칸은 "…/getMidFcst" 줄바꿈 "?serviceKey=…" 줄바꿈 "&stnId=…"이고, LLM은 공백 없이 이어 옮겼다
        ValidatedOperation operation = validate("kma-mid-forecast.docx", TestDocuments.extraction("kma-mid-forecast.json"));

        assertTrue(operation.address().sources().requestExampleUrl().contains("getMidFcst\n?serviceKey="),
                operation.address().sources().requestExampleUrl());
        assertEquals("http://apis.data.go.kr/1360000/MidFcstInfoService/getMidFcst",
                operation.address().candidates().exampleUrl());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of(), operation.removals());
    }

    @Test
    void KCI_응답_예제에_빈_태그로만_있는_필드도_V5를_통과한다() {
        ValidatedOperation operation = validate("kci-reference.docx", TestDocuments.extraction("kci-reference.json"));

        assertTrue(paths(operation).containsAll(List.of("response.body.items.item[].TITLESUB",
                "response.body.items.item[].TRANTITLE", "response.body.items.item[].SOURCE",
                "response.body.items.item[].REFARTIID")), paths(operation).toString());
        assertEquals(14, operation.responseFields().size());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of(), operation.removals());
    }

    @Test
    void 국민연금_둥근_따옴표로_감싼_JSON_키도_V5를_통과한다() {
        ValidatedOperation operation = validate("nps-disability-review.hwp",
                TestDocuments.extraction("nps-disability-review.json"));

        assertFalse(operation.dropped());
        assertEquals(12, operation.responseFields().size());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of(), operation.removals());
        assertEquals("getJgmtSttusInfoSearchV2", operation.operationName());
        assertEquals(List.of("00"), operation.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList(),
                "응답 예제의 “resultCode”: “00”");
    }

    @Test
    void 국민연금_표에만_있는_필드는_예제의_구조를_따른_경로로_통과한다() {
        // 응답 필드 표의 casRsltRltnCnt가 예제에는 casRsltnCnt로 적혀 있다. 표에만 있는 이름은 FIELD_TABLE이다
        LlmExtraction fixture = TestDocuments.extraction("nps-disability-review.json");
        String tableOnly = "response.body.items.item[].casRsltRltnCnt";

        ValidatedOperation operation = validate("nps-disability-review.hwp", fixture);
        assertTrue(paths(operation).contains(tableOnly));

        OperationBuilder wrongSource = OperationBuilder.from(fixture.operations().get(0))
                .fields(field(tableOnly, LlmResponseField.PathSource.RESPONSE_EXAMPLE),
                        field("response.body.items.item[].casRsltnCnt", LlmResponseField.PathSource.RESPONSE_EXAMPLE));
        ValidatedOperation fromExample = validate("nps-disability-review.hwp", extraction(wrongSource));
        assertEquals(List.of("response.body.items.item[].casRsltnCnt"), paths(fromExample),
                "예제에 없는 이름을 예제에서 읽었다고 하면 버린다");
    }

    private ValidatedOperation validate(String document, LlmExtraction extraction) {
        return new ExtractionValidator().validate(extraction, TestDocuments.read(document)).operations().get(0);
    }

    private List<String> paths(ValidatedOperation operation) {
        return operation.responseFields().stream().map(ValidatedOperation.Field::path).toList();
    }
}
