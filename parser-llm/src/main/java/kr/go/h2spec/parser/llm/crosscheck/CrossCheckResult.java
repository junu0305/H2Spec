package kr.go.h2spec.parser.llm.crosscheck;

import kr.go.h2spec.parser.llm.normalize.NormalizedOperation;

import java.util.List;

/**
 * 교차검증 결과.
 *
 * @param operations llm 엔진의 오퍼레이션 (받은 순서). 차이가 있는 IR은 {@code [교차검증]} 노트를 더한 사본이고,
 *                   그 밖의 IR 필드는 바꾸지 않는다
 * @param pairs      짝지은 오퍼레이션
 * @param warnings   rule 엔진만 찾은 오퍼레이션의 경고. CLI가 표준 오류로 출력한다
 */
public record CrossCheckResult(List<NormalizedOperation> operations, List<CrossChecker.Pair> pairs,
                               List<String> warnings) {

    public CrossCheckResult {
        // 목록은 바꿀 수 없게 복사해 둔다
        operations = List.copyOf(operations);
        pairs = List.copyOf(pairs);
        warnings = List.copyOf(warnings);
    }
}
