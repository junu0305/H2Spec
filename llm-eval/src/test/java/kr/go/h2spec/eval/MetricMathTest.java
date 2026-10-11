package kr.go.h2spec.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이름 F1, 재현율·정밀도의 계산. 정답 이름과 엔진 이름을 순서 없이, 같은 이름은 수까지 세어 비교한다.
 */
class MetricMathTest {

    private static final double EPSILON = 1e-9;

    @Test
    void 순서가_달라도_같은_이름이면_모두_맞은_것으로_센다() {
        NameMatch match = NameMatch.of(List.of("serviceKey", "pageNo", "numOfRows"),
                List.of("numOfRows", "serviceKey", "pageNo"));

        assertEquals(new NameMatch(3, 0, 0, List.of(), List.of()), match);
    }

    @Test
    void 정답에만_있는_이름과_엔진에만_있는_이름을_따로_센다() {
        NameMatch match = NameMatch.of(List.of("resultCode", "resultMsg", "stationName", "addr"),
                List.of("resultCode", "stationName", "dmX", "addr"));

        assertEquals(3, match.truePositives());
        assertEquals(1, match.falsePositives());
        assertEquals(1, match.falseNegatives());
        assertEquals(List.of("resultMsg"), match.missing());
        assertEquals(List.of("dmX"), match.extra());
    }

    @Test
    void 경로가_다른_같은_이름은_집합으로_줄이지_않고_수까지_센다() {
        // 응답은 경로의 마지막 칸만 보므로 page와 page.page가 둘 다 page다. 하나만 내면 하나는 빠진 것이다
        NameMatch match = NameMatch.of(List.of("page", "page", "countPerPage"), List.of("page", "countPerPage"));

        assertEquals(2, match.truePositives());
        assertEquals(0, match.falsePositives());
        assertEquals(List.of("page"), match.missing());
    }

    @Test
    void 이름은_대소문자를_가린다() {
        // XML 태그·JSON 키·쿼리 파라미터 이름은 대소문자를 가린다
        NameMatch match = NameMatch.of(List.of("ServiceKey"), List.of("serviceKey"));

        assertEquals(List.of("ServiceKey"), match.missing());
        assertEquals(List.of("serviceKey"), match.extra());
    }

    @Test
    void F1은_오퍼레이션마다의_수를_더해서_계산한다() {
        // 3/4 맞힌 오퍼레이션과 1/1 맞힌 오퍼레이션: 맞음 4, 엔진에만 1, 정답에만 1
        Score score = Score.EMPTY
                .plus(NameMatch.of(List.of("a", "b", "c", "d"), List.of("a", "b", "c", "x")))
                .plus(NameMatch.of(List.of("e"), List.of("e")));

        assertEquals(new Score(4, 1, 1), score);
        assertEquals(0.8, score.precision(), EPSILON);
        assertEquals(0.8, score.recall(), EPSILON);
        assertEquals(0.8, score.f1(), EPSILON);
    }

    @Test
    void 정밀도와_재현율이_다르면_F1은_조화평균이다() {
        // 정밀도 2/2 = 1, 재현율 2/4 = 0.5 → F1 = 2 * 1 * 0.5 / 1.5
        Score score = Score.EMPTY.plus(NameMatch.of(List.of("a", "b", "c", "d"), List.of("a", "b")));

        assertEquals(1.0, score.precision(), EPSILON);
        assertEquals(0.5, score.recall(), EPSILON);
        assertEquals(2.0 / 3.0, score.f1(), EPSILON);
    }

    @Test
    void 맞은_것이_없으면_F1은_0이고_셀_것이_없으면_값이_없다() {
        Score wrong = Score.EMPTY.plus(NameMatch.of(List.of("a"), List.of("b")));

        assertEquals(0.0, wrong.f1(), EPSILON);
        assertTrue(Double.isNaN(Score.EMPTY.f1()));
        assertTrue(Double.isNaN(Score.EMPTY.precision()));
        assertEquals(Markdown.NONE, Markdown.decimal(Score.EMPTY.f1()), "값이 없으면 리포트에 -로 적는다");
    }

    @Test
    void 비율은_분자와_분모를_함께_남긴다() {
        Ratio ratio = Ratio.EMPTY.plus(true).plus(false).plus(new Ratio(4, 8));

        assertEquals(new Ratio(5, 10), ratio);
        assertEquals(0.5, ratio.value(), EPSILON);
        assertEquals("50.0% (5/10)", Markdown.percent(ratio.hits(), ratio.total()));
        assertEquals(Markdown.NONE, Markdown.percent(0, 0));
        assertTrue(Double.isNaN(Ratio.EMPTY.value()));
    }

    @Test
    void 주소는_끝_슬래시만_무시하고_스킴은_가린다() {
        // jeju-folksong의 서비스 URL은 http, END POINT는 https다. 스킴을 무시하면 이 차이를 놓친다
        assertTrue(BaselineEvaluator.sameUrl("https://a.go.kr/svc/getList/", "https://a.go.kr/svc/getList"));
        assertEquals(false, BaselineEvaluator.sameUrl("https://a.go.kr/svc/getList", "http://a.go.kr/svc/getList"));
        assertEquals(false, BaselineEvaluator.sameUrl("https://a.go.kr/svc/getList", "https://a.go.kr/svc/getlist"));
    }
}
