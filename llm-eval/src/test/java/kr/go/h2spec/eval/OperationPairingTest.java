package kr.go.h2spec.eval;

import kr.go.h2spec.eval.OperationPairing.Key;
import kr.go.h2spec.eval.OperationPairing.Match;
import kr.go.h2spec.eval.OperationPairing.Method;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 정답과 엔진의 오퍼레이션 짝짓기 (설계 9.4): 영문 오퍼레이션명 → 국문 기능명 → 문서 안 순서. 주소는 보지 않는다.
 */
class OperationPairingTest {

    @Test
    void 지금_정답에는_이름과_기능명이_없어_문서_순서로_짝짓는다() {
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key(null, null), new Key(null, null)),
                List.of(new Key("selectAreaInfoLst", null), new Key("evrfrndFarmngLst", null)));

        assertEquals(List.of(new Match(0, 0, Method.ORDER), new Match(1, 1, Method.ORDER)), result.matches());
        assertEquals(List.of(), result.unmatchedGold());
        assertEquals(List.of(), result.unmatchedEngine());
    }

    @Test
    void 영문_오퍼레이션명이_같으면_순서가_달라도_짝짓는다() {
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key("getMsrstnList", "측정소 목록 조회"), new Key("getNearbyMsrstnList", "근접측정소 목록 조회")),
                List.of(new Key("getNearbyMsrstnList", null), new Key("getMsrstnList", null)));

        assertEquals(List.of(new Match(0, 1, Method.NAME), new Match(1, 0, Method.NAME)), result.matches());
    }

    @Test
    void 이름이_없으면_공백을_무시한_국문_기능명으로_짝짓는다() {
        // 칸 폭 때문에 꺾이거나 판마다 띄어쓰기가 다른 기능명
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key("", "측정소 목록 조회"), new Key("", "TM 기준좌표 조회")),
                List.of(new Key(null, "TM기준좌표 조회"), new Key(null, "측정소\n목록 조회")));

        assertEquals(List.of(new Match(0, 1, Method.TITLE), new Match(1, 0, Method.TITLE)), result.matches());
    }

    @Test
    void 이름과_기능명으로_짝지은_뒤_남은_것끼리_순서대로_짝짓는다() {
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key(null, null), new Key("getB", null), new Key(null, null)),
                List.of(new Key(null, "가"), new Key(null, "나"), new Key("getB", null)));

        // getB는 이름으로, 나머지 정답 두 개는 남은 엔진 오퍼레이션 두 개와 문서 순서대로
        assertEquals(List.of(new Match(0, 0, Method.ORDER), new Match(1, 2, Method.NAME),
                new Match(2, 1, Method.ORDER)), result.matches());
    }

    @Test
    void 주소가_틀려도_짝이_지어져_틀린_주소로_셀_수_있다() {
        // 주소로 짝지으면 "못 찾음 하나 + 엉뚱한 것 하나"로 갈린다. 이름표는 주소를 담지 않는다
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key("getList", null)), List.of(new Key("getList", null)));

        assertEquals(List.of(new Match(0, 0, Method.NAME)), result.matches());
    }

    @Test
    void 짝이_없는_정답은_못_찾은_것이고_짝이_없는_엔진_오퍼레이션은_정답에_없는_것이다() {
        OperationPairing.Result fewer = OperationPairing.pair(
                List.of(new Key(null, null), new Key(null, null), new Key(null, null)),
                List.of(new Key(null, null)));
        OperationPairing.Result more = OperationPairing.pair(
                List.of(new Key("getA", null)),
                List.of(new Key("getZ", null), new Key("getA", null), new Key("getY", null)));

        assertEquals(List.of(1, 2), fewer.unmatchedGold());
        assertEquals(List.of(new Match(0, 1, Method.NAME)), more.matches());
        assertEquals(List.of(0, 2), more.unmatchedEngine());
    }

    @Test
    void 같은_이름이_여럿이면_문서에서_앞의_것부터_쓴다() {
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key("getList", null), new Key("getList", null)),
                List.of(new Key("getList", null), new Key("getList", null)));

        assertEquals(List.of(new Match(0, 0, Method.NAME), new Match(1, 1, Method.NAME)), result.matches());
    }

    @Test
    void 이름이_다른_엔진_오퍼레이션도_남으면_순서로_짝지어_틀린_값을_센다() {
        // 엔진이 오퍼레이션명을 잘못 읽어도 정답과 짝이 지어져야 주소·필드 차이로 드러난다
        OperationPairing.Result result = OperationPairing.pair(
                List.of(new Key("getList", "목록 조회")), List.of(new Key("getLst", "목록")));

        assertEquals(List.of(new Match(0, 0, Method.ORDER)), result.matches());
    }
}
