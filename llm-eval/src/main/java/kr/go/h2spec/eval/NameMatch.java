package kr.go.h2spec.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정답 이름 목록과 엔진 이름 목록을 비교한 결과. 순서는 보지 않고 같은 이름이 여러 번 나오면 그 수까지 센다.
 * 응답 필드는 경로의 마지막 칸만 보므로 다른 경로의 같은 이름(page와 page.page)이 겹칠 수 있어 집합으로 줄이지 않는다.
 *
 * @param truePositives  양쪽에 있는 이름 수
 * @param falsePositives 엔진에만 있는 이름 수
 * @param falseNegatives 정답에만 있는 이름 수
 * @param missing        정답에만 있는 이름 (정답 순서)
 * @param extra          엔진에만 있는 이름 (엔진 순서)
 */
record NameMatch(int truePositives, int falsePositives, int falseNegatives, List<String> missing, List<String> extra) {

    /**
     * 두 목록을 비교한다. 이름은 대소문자를 가린다. XML 태그·JSON 키·쿼리 파라미터 이름이 대소문자를 가리기 때문이다.
     *
     * @param expected 정답 이름
     * @param actual   엔진 이름
     * @return 비교 결과
     */
    static NameMatch of(List<String> expected, List<String> actual) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        actual.forEach(name -> remaining.merge(name, 1, Integer::sum));
        List<String> missing = new ArrayList<>();
        int truePositives = 0;
        for (String name : expected) {
            Integer left = remaining.get(name);
            if (left == null || left == 0) {
                missing.add(name);
            } else {
                remaining.put(name, left - 1);
                truePositives++;
            }
        }
        List<String> extra = new ArrayList<>();
        Map<String, Integer> surplus = new LinkedHashMap<>(remaining);
        for (String name : actual) {
            int left = surplus.getOrDefault(name, 0);
            if (left > 0) {
                extra.add(name);
                surplus.put(name, left - 1);
            }
        }
        return new NameMatch(truePositives, extra.size(), missing.size(), List.copyOf(missing), List.copyOf(extra));
    }
}
