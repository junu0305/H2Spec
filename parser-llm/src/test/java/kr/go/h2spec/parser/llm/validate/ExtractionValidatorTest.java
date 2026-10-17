package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.llm.LlmFixtures;
import kr.go.h2spec.parser.llm.SampleDocument;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.extraction;
import static kr.go.h2spec.parser.llm.LlmFixtures.field;
import static kr.go.h2spec.parser.llm.LlmFixtures.operation;
import static kr.go.h2spec.parser.llm.LlmFixtures.query;
import static kr.go.h2spec.parser.llm.LlmFixtures.success;
import static kr.go.h2spec.parser.llm.SampleDocument.SERVICE;
import static kr.go.h2spec.parser.llm.SampleDocument.URL;
import static kr.go.h2spec.parser.llm.SampleDocument.heading;
import static kr.go.h2spec.parser.llm.SampleDocument.row;
import static kr.go.h2spec.parser.llm.SampleDocument.standardOperation;
import static kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource.FIELD_TABLE;
import static kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource.RESPONSE_EXAMPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증 규칙(V1~V12) 가운데 주요 규칙을 하나씩 본다. 문서는 {@link SampleDocument}의 작은 표준 기술문서이고,
 * LLM 출력은 그 문서를 그대로 옮긴 것({@link SampleDocument#standardOperation()})에서 규칙마다 한 곳만 바꾼다.
 */
class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    @Test
    void 원문과_맞는_LLM_출력은_그대로_통과한다() {
        ValidatedOperation operation = validate(standardOperation());

        assertFalse(operation.dropped());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of(), operation.removals());
        assertEquals("getMsrstnList", operation.operationName());
        assertEquals("측정소 목록 조회", operation.title());
        assertEquals(URL, operation.address().url());
        assertEquals(SERVICE, operation.address().parts().baseUrl());
        assertEquals("/getMsrstnList", operation.address().parts().endpoint());
        assertEquals("MsrstnList", operation.address().parts().apiId());
        assertEquals(List.of("serviceKey", "returnType", "numOfRows", "pageNo", "stationName"), names(operation));
        assertEquals(List.of("response.header.resultCode", "response.header.resultMsg", "response.body.totalCount",
                "response.body.items.item[].stationName", "response.body.items.item[].sidoName",
                "response.body.items.item[].stationCode"), paths(operation));
        assertEquals(List.of("00", "00"), operation.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList());
        assertEquals(List.of("01", "30"), operation.errorCodes().stream().map(code -> code.code()).toList());
    }

    // ---- V1 ----

    @Test
    void V1_원문에_없는_주소_후보는_버리고_노트를_남긴다() {
        String fake = SERVICE + "/getFakeList";

        ValidatedOperation operation = validate(standardOperation().tableUrl(fake));

        assertNull(operation.address().sources().tableUrl());
        assertNull(operation.address().candidates().tableUrl());
        assertEquals(List.of("[검증] 원문에서 찾지 못한 주소 후보를 버림 (tableUrl): " + fake), operation.notes());
        assertEquals(List.of("V1"), rules(operation));
        assertEquals(URL, operation.address().url(), "남은 후보로 고른다");
    }

    @Test
    void 주소는_V1을_통과한_후보로_rule_엔진과_같은_규칙이_고른다() {
        // Call Back URL이 없으면 마지막 경로 칸에 오퍼레이션명이 든 요청 예제를 쓴다
        ValidatedOperation fromExample = validate(standardOperation().callBackUrl(""));
        assertEquals(URL, fromExample.address().url());
        assertEquals(List.of(), fromExample.address().selectionNotes());

        // 원문에서 확인하지 못한 오퍼레이션명은 서비스 URL에 붙이지 않는다. 남은 후보가 서비스 URL뿐이면 그것을 쓰고 알린다
        ValidatedOperation serviceOnly = validate(standardOperation().callBackUrl("").requestExampleUrl("").name("getFake"));
        assertEquals(SERVICE, serviceOnly.address().url());
        assertEquals(List.of("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀"),
                serviceOnly.address().selectionNotes());
    }

    @Test
    void V1_영문_오퍼레이션명은_rule_엔진처럼_정보_표의_이름_칸에서만_받고_목록_표에만_있으면_노트_없이_버린다() {
        // 정보 표에 이름 칸이 없고 오퍼레이션 목록 표에만 영문명이 있는 문서 (rule 엔진도 이름을 쓰지 않는다)
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(3, SampleDocument.table(row("상세기능명(국문)", "측정소 목록 조회"), row("Call Back URL", URL)));
        blocks.add(SampleDocument.table(row("번호", "오퍼레이션명(영문)", "오퍼레이션명(국문)"),
                row("1", "getMsrstnList", "측정소 목록 조회")));

        ValidatedOperation listOnly = validate(blocks, standardOperation());
        ValidatedOperation invented = validate(standardOperation().name("getInventedList"));

        assertNull(listOnly.operationName());
        assertEquals(List.of(), listOnly.notes(), "문서에 있는 이름이라 검수할 거리가 아니다");
        assertEquals(List.of("V1"), rules(listOnly));
        assertNull(invented.operationName());
        assertEquals(List.of("[검증] 원문의 표에서 찾지 못한 영문 오퍼레이션명을 버림: getInventedList"), invented.notes());
    }

    @Test
    void V1_주소_후보를_찾은_칸에_다른_주소가_먼저_있으면_rule_엔진처럼_첫_주소를_쓰고_알린다() {
        String other = SERVICE + "/getOtherList";
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(9, SampleDocument.table(row("요청메시지"), row(other + "?serviceKey=키\n" + SampleDocument.EXAMPLE_URL),
                row("응답메시지"), row(SampleDocument.RESPONSE_EXAMPLE)));

        ValidatedOperation operation = validate(blocks, standardOperation());

        assertEquals(other, operation.address().candidates().exampleUrl());
        assertEquals(List.of("[검증] 주소 후보를 찾은 칸에 다른 주소가 먼저 있어 rule 엔진처럼 칸의 첫 주소를 씀 (1개): "
                + "requestExampleUrl " + SampleDocument.EXAMPLE_URL + " → " + other), operation.notes());
        assertEquals(List.of(), validate(standardOperation()).notes(), "질의 문자열만 다른 같은 주소는 알리지 않는다");
    }

    // ---- V2 ----

    @Test
    void V2_고른_주소가_형식에_맞지_않으면_오퍼레이션을_건너뛴다() {
        assertDroppedFor(SERVICE + "/getMsrstnList#top", "주소에 쓸 수 없는 글자(#)가 있습니다");
        assertDroppedFor(SERVICE + "\\getMsrstnList", "주소에 쓸 수 없는 글자(\\)가 있습니다");
        assertDroppedFor("http://{host}/MsrstnInfoInqireSvc/getMsrstnList", "경로 변수가 endpoint가 아니라 baseUrl에 있습니다");
        // 호스트만 있는 주소는 rule 엔진과 같은 주소 나누기에서 걸린다. 호스트를 오퍼레이션으로 나누면 baseUrl이 "http:/"가 된다
        assertDroppedFor("http://apis.data.go.kr", "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다");
        assertDroppedFor("http://{host}", "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다");
    }

    // ---- V3, V4 ----

    @Test
    void V3_원문에_없는_요청_파라미터는_제거하고_노트를_남긴다() {
        ValidatedOperation operation = validate(standardOperation().addParameter(query("sigunguName")).addParameter(query("No")));

        assertEquals(List.of("serviceKey", "returnType", "numOfRows", "pageNo", "stationName"), names(operation));
        assertEquals(List.of("[검증] 원문에서 찾지 못한 요청 파라미터를 제거함 (2개): sigunguName, No"), operation.notes(),
                "pageNo의 No처럼 다른 이름의 일부는 원문에 있는 것으로 보지 않는다");
        assertEquals(List.of("V3", "V3"), rules(operation));
    }

    @Test
    void V4_생성_코드에_쓸_수_없는_이름의_요청_파라미터는_제거하고_노트를_남긴다() {
        List<Block> blocks = withRequestTable(
                row("item.name", "항목", "10", "0", "", "점이 든 이름"),
                row("class", "구분", "10", "0", "", "예약어"),
                row("a'b", "따옴표", "10", "0", "", "따옴표가 든 이름"),
                row("page-no", "페이지", "4", "0", "1", "Java 이름은 pageNo"),
                row("검색어", "검색어", "10", "0", "", "한글 이름"));

        ValidatedOperation operation = validate(blocks, standardOperation()
                .parameters(query("item.name"), query("class"), query("a'b"), query("page-no"), query("검색어")));

        // 예약어(class)는 generator가 class_로 고쳐 인자로 받는다
        assertEquals(List.of("class", "page-no", "검색어"), names(operation), "공백·한글·붙임표·예약어 이름은 생성된다");
        assertEquals(List.of("[검증] 생성 코드에 쓸 수 없는 이름의 요청 파라미터를 제거함 (2개): item.name, a'b"),
                operation.notes());
    }

    // ---- V5 ----

    @Test
    void V5_마지막_칸은_pathSource가_가리키는_곳에_있어야_한다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.add(heading("※ dataTime은 측정일시다"));

        ValidatedOperation operation = validate(blocks, standardOperation().fields(
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE),
                field("response.body.items.item[].stationCode", FIELD_TABLE),
                // stationCode는 응답 필드 표에만 있고 응답 예제의 태그로는 없다
                field("response.body.items.item[].stationCode", RESPONSE_EXAMPLE),
                // dataTime은 표가 아니라 문단에만 있다
                field("response.body.items.item[].dataTime", FIELD_TABLE)));

        assertEquals(List.of("response.body.items.item[].stationName", "response.body.items.item[].stationCode"),
                paths(operation));
        assertEquals(List.of(
                "[검증] 마지막 칸을 응답 예제의 키·태그에서 찾지 못한 응답 필드를 제거함 (1개): response.body.items.item[].stationCode",
                "[검증] 마지막 칸을 응답 필드 표에서 찾지 못한 응답 필드를 제거함 (1개): response.body.items.item[].dataTime"),
                operation.notes());
    }

    // ---- V6, V12 ----

    @Test
    void V6_V12_생성_DTO에서_필드나_getter가_겹치거나_getClass가_되는_응답_필드는_제거하고_노트를_남긴다() {
        List<Block> blocks = withResponseTable(
                row("sido", "시도", "20", "1", "서울", "시도"),
                row("_sido", "시도 구분", "20", "1", "서울", "getter가 getSido로 sido와 같다"),
                row("station_name", "측정소명", "30", "1", "종로구", "필드 이름이 stationName과 같다"),
                row("_class", "구분", "10", "1", "1", "getter가 getClass"));

        ValidatedOperation operation = validate(blocks, standardOperation().fields(
                field("response.body.items.item[].stationName", FIELD_TABLE),
                field("response.body.items.item[].sido", FIELD_TABLE),
                field("response.body.items.item[]._sido", FIELD_TABLE),
                field("response.body.items.item[].station_name", FIELD_TABLE),
                field("response.body.items.item[]._class", FIELD_TABLE)));

        assertEquals(List.of("response.body.items.item[].stationName", "response.body.items.item[].sido"),
                paths(operation));
        assertEquals(List.of(
                "[검증] 이름 규칙에 맞지 않는 응답 경로를 제거함 (1개): response.body.items.item[]._class",
                "[검증] Java 이름이 같은 자리의 앞 필드와 겹치는 응답 필드를 제거함 (2개): "
                        + "response.body.items.item[]._sido(sido와 겹침), "
                        + "response.body.items.item[].station_name(stationName와 겹침)"),
                operation.notes());
        assertEquals(List.of("V6", "V12", "V12"), rules(operation));
    }

    // ---- V8 ----

    @Test
    void V8_성공코드_후보는_출처가_가리키는_자리에_있고_코드_꼴이어야_하며_버려도_노트를_남기지_않는다() {
        ValidatedOperation operation = validate(standardOperation().resultCode("resultCode",
                success("00", LlmSuccessCode.Source.FIELD_TABLE),
                // 응답 예제의 결과코드 값은 00이다
                success("0", LlmSuccessCode.Source.RESPONSE_EXAMPLE),
                success("00", LlmSuccessCode.Source.ERROR_TABLE),
                // 에러코드 표의 01 행에는 정상·NORMAL이 없다
                success("01", LlmSuccessCode.Source.ERROR_TABLE),
                success("INFO-00", LlmSuccessCode.Source.FIELD_TABLE),
                success("00 OK", LlmSuccessCode.Source.RESPONSE_EXAMPLE)));

        assertEquals(List.of("00:FIELD_TABLE", "00:ERROR_TABLE"), operation.successCodes().stream()
                .map(code -> code.code() + ":" + code.source()).toList());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of("V8", "V8", "V8", "V8"), rules(operation));
        assertTrue(operation.successCodes().get(0).position() < operation.successCodes().get(1).position(),
                "찾은 칸의 문서 순서를 담는다");
    }

    @Test
    void V8_정상_행은_비정상_행과_응답_필드_표의_결과메시지_행을_빼고_찾는다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(11, SampleDocument.table(row("에러코드", "에러메시지", "설명"),
                row("00", "NORMAL SERVICE", "정상"),
                row("99", "UNKNOWN ERROR", "비정상 종료"),
                row("98", "ABNORMAL STATE", "abnormal")));

        ValidatedOperation operation = validate(blocks, standardOperation().resultCode("resultCode",
                success("00", LlmSuccessCode.Source.ERROR_TABLE),
                success("99", LlmSuccessCode.Source.ERROR_TABLE),
                success("98", LlmSuccessCode.Source.ERROR_TABLE),
                // 응답 필드 표의 resultMsg 행(샘플 NORMAL SERVICE)의 크기 칸
                success("50", LlmSuccessCode.Source.ERROR_TABLE)));

        assertEquals(List.of("00"), operation.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList());
    }

    @Test
    void V8_결과코드_필드_이름은_원문에_있어야_하고_결과메시지_필드는_받지_않는다() {
        ValidatedOperation standard = validate(standardOperation());
        ValidatedOperation message = validate(standardOperation().resultCode("resultMsg",
                success("00", LlmSuccessCode.Source.FIELD_TABLE)));
        ValidatedOperation invented = validate(standardOperation().resultCode("response.header.resultCd",
                success("00", LlmSuccessCode.Source.FIELD_TABLE)));

        assertEquals("resultCode", standard.resultCodeField());
        assertEquals("", message.resultCodeField(), "인터셉터가 결과메시지를 결과코드로 읽으면 정상 응답이 모두 예외가 된다");
        assertEquals(List.of("[검증] 결과메시지 필드라 결과코드 필드로 쓰지 않음 (1개): resultMsg"), message.notes());
        assertEquals("", invented.resultCodeField());
        assertEquals(List.of("[검증] 원문에서 찾지 못한 결과코드 필드 이름을 버림 (1개): response.header.resultCd"),
                invented.notes());
        assertEquals(List.of("00"), message.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList(),
                "성공코드 후보는 기본 결과코드 이름의 행에서 찾는다");
    }

    // ---- V11 ----

    @Test
    void V11_바로잡은_뒤에도_주소가_같은_오퍼레이션은_뒤의_것을_건너뛰고_노트를_남긴다() {
        List<ValidatedOperation> operations = validator.validate(extraction(
                standardOperation(), standardOperation().name("").title("측정소 목록 다시 조회")),
                SampleDocument.standard()).operations();

        assertFalse(operations.get(0).dropped());
        assertEquals(List.of("[검증] 주소가 같은 뒤 오퍼레이션을 건너뜀 (1개): 측정소 목록 다시 조회"), operations.get(0).notes());
        assertEquals("주소가 앞 오퍼레이션(getMsrstnList)과 같습니다: " + URL, operations.get(1).dropReason());
    }

    // ---- 회사 응답의 null ----

    @Test
    void 회사_응답의_null은_빈_값으로_보고_예외_없이_건너뛴다() {
        LlmOperation empty = new LlmOperation(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        ValidatedOperation operation = validator.validate(new LlmExtraction(null, null, null, List.of(empty), null),
                SampleDocument.standard()).operations().get(0);

        assertTrue(operation.dropped());
        assertEquals("", operation.title());
    }

    // ---- 도우미 ----

    private ValidatedOperation validate(LlmFixtures.OperationBuilder operation) {
        return validate(SampleDocument.standard(), operation);
    }

    private ValidatedOperation validate(List<Block> blocks, LlmFixtures.OperationBuilder operation) {
        return validator.validate(extraction(operation), blocks).operations().get(0);
    }

    private void assertDroppedFor(String callBackUrl, String reason) {
        List<Block> blocks = withInfoTable(row("Call Back URL", callBackUrl));

        ValidatedOperation operation = validate(blocks,
                standardOperation().callBackUrl(callBackUrl).requestExampleUrl("").serviceUrl(""));

        assertTrue(operation.dropped(), callBackUrl);
        assertTrue(operation.dropReason().contains(reason), operation.dropReason());
    }

    @SafeVarargs
    private List<Block> withInfoTable(List<String>... rows) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> info = new ArrayList<>(List.of(row("상세기능명(국문)", "측정소 목록 조회"),
                row("오퍼레이션명(영문)", "getMsrstnList")));
        for (List<String> row : rows) {
            info.removeIf(existing -> existing.get(0).equals(row.get(0)));
            info.add(row);
        }
        blocks.set(3, new Block.Table(info));
        return blocks;
    }

    @SafeVarargs
    private List<Block> withRequestTable(List<String>... rows) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> request = new ArrayList<>(((Block.Table) blocks.get(5)).rows());
        request.addAll(List.of(rows));
        blocks.set(5, new Block.Table(request));
        return blocks;
    }

    @SafeVarargs
    private List<Block> withResponseTable(List<String>... rows) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> response = new ArrayList<>(((Block.Table) blocks.get(7)).rows());
        response.addAll(List.of(rows));
        blocks.set(7, new Block.Table(response));
        return blocks;
    }

    private List<String> names(ValidatedOperation operation) {
        return operation.requestParameters().stream().map(ValidatedOperation.Parameter::name).toList();
    }

    private List<String> paths(ValidatedOperation operation) {
        return operation.responseFields().stream().map(ValidatedOperation.Field::path).toList();
    }

    private List<String> rules(ValidatedOperation operation) {
        return operation.removals().stream().map(ValidatedOperation.Removal::rule).toList();
    }
}
