package kr.go.h2spec.eval;

import kr.go.h2spec.eval.DetailMetric.Check;
import kr.go.h2spec.eval.GoldFile.GoldField;
import kr.go.h2spec.eval.GoldFile.GoldParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0-4에서 정답에 더하는 키(설계 9.2)로 재는 지표. 지금 corpus 정답에는 키가 없어 꺼져 있으므로, 키가 들어오면
 * 바로 맞게 재는지 손으로 만든 정답과 IR로 확인한다.
 */
class DetailMetricTest {

    @Test
    void 새_키가_없는_정답은_아무_지표도_재지_않는다() {
        Map<DetailMetric, Check> checks = DetailMetric.checkAll(GoldBuilder.gateway("getList").build(),
                IrBuilder.gateway("getList").operation(null, null));

        assertTrue(checks.isEmpty(), checks.toString());
    }

    @Test
    void 응답_경로는_전체_경로가_같아야_맞다() {
        // jeju처럼 루트가 여럿인 JSON을 rule 엔진은 response.body.items.item[] 아래에 둔다
        EngineOperation rule = new IrBuilder("Jeju", "https://open.jejudatahub.net/api/proxy", "/7ttt/{your_appkey}")
                .field("response.body.items.item[].totCnt", "integer")
                .field("response.body.items.item[].baseDate", "integer")
                .field("hasMore", "boolean")
                .operation(null, null);
        var gold = GoldBuilder.gateway("x").responseDetail(new GoldField("totCnt", "integer"),
                new GoldField("hasMore", "boolean"), new GoldField("data[].baseDate", "string")).build();

        Check path = DetailMetric.RESPONSE_PATH.check(gold, rule);
        Check type = DetailMetric.RESPONSE_TYPE.check(gold, rule);

        assertEquals(new Check(3, 1, List.of("IR에 없음: totCnt", "IR에 없음: data[].baseDate")), path);
        assertEquals(new Check(1, 1, List.of()), type, "타입은 경로가 맞은 필드만 본다");
    }

    @Test
    void 응답_타입은_대소문자를_무시하고_비교한다() {
        EngineOperation engine = IrBuilder.gateway("getList").field("response.body.totalCount", "integer")
                .operation(null, null);
        var gold = GoldBuilder.gateway("getList").responseDetail(
                new GoldField("response.body.items.item[].stationName", "STRING"),
                new GoldField("response.body.totalCount", "string")).build();

        Check type = DetailMetric.RESPONSE_TYPE.check(gold, engine);

        assertEquals(new Check(2, 1, List.of("response.body.totalCount: 정답 string, IR integer")), type);
    }

    @Test
    void 요청_필수_여부는_이름이_맞고_정답에_필수_여부가_있는_파라미터만_본다() {
        EngineOperation engine = IrBuilder.gateway("getList").param("pageNo", "query", false)
                .param("sido", "query", false).operation(null, null);
        var gold = GoldBuilder.gateway("getList").requestDetail(
                new GoldParameter("serviceKey", "query", "string", true),
                new GoldParameter("sido", "query", "string", true),
                new GoldParameter("pageNo", "query", "integer", null),
                new GoldParameter("notInIr", "query", "string", true)).build();

        Check required = DetailMetric.REQUEST_REQUIRED.check(gold, engine);

        assertEquals(new Check(2, 1, List.of("sido: 정답 필수, IR 선택")), required);
    }

    @Test
    void 응답_포맷은_XML_JSON_정답만_잰다() {
        EngineOperation xml = IrBuilder.gateway("getList").operation(null, null);

        assertEquals(new Check(1, 0, List.of("정답 JSON, IR XML")),
                DetailMetric.RESPONSE_FORMAT.check(GoldBuilder.gateway("getList").responseFormat("JSON").build(), xml));
        assertEquals(new Check(1, 1, List.of()),
                DetailMetric.RESPONSE_FORMAT.check(GoldBuilder.gateway("getList").responseFormat("xml").build(), xml));
        // BOTH는 기본 포맷을 모르면 맞는 IR 값을 정할 수 없다
        assertEquals(null,
                DetailMetric.RESPONSE_FORMAT.check(GoldBuilder.gateway("getList").responseFormat("BOTH").build(), xml));
    }

    @Test
    void 문서에_성공코드가_없으면_00과_노트가_함께_있어야_맞다() {
        var gold = GoldBuilder.gateway("getList").successCode("").build();
        EngineOperation ruleLike = IrBuilder.gateway("getList").operation(null, null);
        EngineOperation noted = IrBuilder.gateway("getList")
                .note("[정규화] 문서에 성공코드가 없어 00을 넣음").operation(null, null);

        assertEquals(0, DetailMetric.SUCCESS_CODE.check(gold, ruleLike).correct(),
                "rule 엔진은 노트 없이 00을 쓰므로 틀린 것으로 센다");
        assertEquals(1, DetailMetric.SUCCESS_CODE.check(gold, noted).correct());
    }

    @Test
    void 성공코드는_글자까지_같아야_맞다() {
        // 선관위 개표소: 표 00, 예제 INFO-00. 정책은 예제를 따라 INFO-00을 고른다
        var gold = GoldBuilder.gateway("getList").successCode("INFO-00", "00", "INFO-00").build();
        EngineOperation rule = IrBuilder.gateway("getList").operation(null, null);

        Check check = DetailMetric.SUCCESS_CODE.check(gold, rule);

        assertEquals(new Check(1, 0, List.of("정답 INFO-00, IR 00")), check);
    }

    @Test
    void 성공코드_후보가_엇갈리거나_0을_00으로_바꾼_문서는_노트를_남겨야_맞다() {
        EngineOperation silent = IrBuilder.gateway("getList").operation(null, null);
        EngineOperation noted = IrBuilder.gateway("getList")
                .note("[검증] 성공코드 후보가 엇갈림: 00(응답 필드 표), 0(응답 예제)").operation(null, null);
        var conflicting = GoldBuilder.gateway("getList").successCode("00", "00", "0").build();
        var zeroOnly = GoldBuilder.gateway("getList").successCode("00", "0").build();
        var agreeing = GoldBuilder.gateway("getList").successCode("00", "00", "00").build();

        assertEquals(new Check(1, 0, List.of("후보 [00, 0]인데 성공코드 노트가 없음")),
                DetailMetric.SUCCESS_CODE_NOTE.check(conflicting, silent));
        assertEquals(1, DetailMetric.SUCCESS_CODE_NOTE.check(conflicting, noted).correct());
        assertTrue(DetailMetric.needsSuccessCodeNote(List.of("0")), "khoa처럼 0뿐이어도 00으로 바꾸므로 노트가 필요하다");
        assertEquals(0, DetailMetric.SUCCESS_CODE_NOTE.check(zeroOnly, silent).correct());
        assertEquals(null, DetailMetric.SUCCESS_CODE_NOTE.check(agreeing, silent), "엇갈리지 않으면 재지 않는다");
    }

    @Test
    void 인증_방식은_지금_IR의_authType을_정답_이름으로_바꿔_비교한다() {
        EngineOperation rule = IrBuilder.gateway("getList").operation(null, null);
        EngineOperation extended = IrBuilder.gateway("getList").auth("PATH_KEY", "your_appkey").operation(null, null);

        assertEquals(new Check(1, 0, List.of("정답 PATH_KEY, IR SERVICE_KEY_QUERY")),
                DetailMetric.AUTH.check(GoldBuilder.gateway("getList").auth("PATH_KEY").build(), rule));
        assertEquals(1, DetailMetric.AUTH.check(GoldBuilder.gateway("getList").auth("SERVICE_KEY_QUERY").build(), rule)
                .correct());
        assertEquals(1, DetailMetric.AUTH.check(GoldBuilder.gateway("getList").auth("PATH_KEY").build(), extended)
                .correct(), "3-1의 api.auth가 있으면 그 종류를 쓴다");
    }

    @Test
    void 범위_밖_판정은_정답이_false면_건너뛰고_true면_건너뛰지_않아야_맞다() {
        EngineOperation rule = IrBuilder.gateway("getList").operation(null, null);
        EngineOperation skipped = new EngineOperation(null, null, IrBuilder.gateway("getList").build(),
                "인증키 파라미터 이름이 serviceKey가 아님", true);
        var outOfScope = GoldBuilder.gateway("getList").supportedByGenerator(false).build();
        var inScope = GoldBuilder.gateway("getList").supportedByGenerator(true).build();

        assertEquals(new Check(1, 0, List.of("지원 범위 밖인데 건너뛰지 않음")), DetailMetric.SCOPE.check(outOfScope, rule),
                "rule 엔진은 범위를 판정하지 않아 범위 밖 오퍼레이션에서 늘 틀린다");
        assertEquals(1, DetailMetric.SCOPE.check(outOfScope, skipped).correct());
        assertEquals(1, DetailMetric.SCOPE.check(inScope, rule).correct());
        assertEquals(0, DetailMetric.SCOPE.check(inScope, skipped).correct());
    }

    @Test
    void 키가_있는_지표만_재고_JSON_이름은_낙타_표기다() {
        var gold = GoldBuilder.gateway("getList").responseFormat("XML").auth("SERVICE_KEY_QUERY").build();

        Map<DetailMetric, Check> checks = DetailMetric.checkAll(gold, IrBuilder.gateway("getList").operation(null, null));

        assertEquals(List.of(DetailMetric.RESPONSE_FORMAT, DetailMetric.AUTH), List.copyOf(checks.keySet()));
        assertEquals("successCodeNote", DetailMetric.SUCCESS_CODE_NOTE.jsonKey());
        assertFalse(DetailMetric.SCOPE.hasKey(gold));
    }
}
