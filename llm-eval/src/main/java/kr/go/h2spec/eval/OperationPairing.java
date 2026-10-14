package kr.go.h2spec.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 정답 오퍼레이션과 엔진 오퍼레이션을 짝짓는다. 영문 오퍼레이션명, 국문 기능명 순으로 맞추고
 * 남은 것은 문서 안 순서대로 짝짓는다. 교차검증과 같은 순서다.
 * <p>
 * 주소로는 짝짓지 않는다. 주소가 틀린 오퍼레이션이 "못 찾음 하나 + 엉뚱한 것 하나"로 갈려 틀린 주소를 셀 수 없기 때문이다.
 * 엔진이 건너뛴 오퍼레이션도 짝짓기에 넣는다. 빼면 순서로 짝지을 때 뒤 오퍼레이션이 한 칸씩 밀린다.
 */
final class OperationPairing {

    private OperationPairing() {
    }

    /** 짝을 지은 근거 */
    enum Method {
        /** 영문 오퍼레이션명이 같다 */
        NAME("이름"),
        /** 국문 기능명이 같다 (공백 무시) */
        TITLE("기능명"),
        /** 이름·기능명으로 짝짓고 남은 것을 문서 순서대로 */
        ORDER("순서");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        /** @return 리포트에 쓰는 이름 */
        String label() {
            return label;
        }
    }

    /**
     * 짝짓기에 쓰는 오퍼레이션의 이름표.
     *
     * @param name  영문 오퍼레이션명. 없으면 null이나 빈 문자열
     * @param title 국문 기능명. 없으면 null이나 빈 문자열
     */
    record Key(String name, String title) {
    }

    /**
     * 짝 하나.
     *
     * @param gold   정답 오퍼레이션의 자리 (0부터)
     * @param engine 엔진 오퍼레이션의 자리 (0부터)
     * @param method 짝을 지은 근거
     */
    record Match(int gold, int engine, Method method) {
    }

    /**
     * 짝짓기 결과.
     *
     * @param matches         짝 (정답 순서)
     * @param unmatchedGold   짝이 없는 정답 오퍼레이션의 자리. 엔진이 못 찾은 것이다
     * @param unmatchedEngine 짝이 없는 엔진 오퍼레이션의 자리. 정답에 없는 것이다
     */
    record Result(List<Match> matches, List<Integer> unmatchedGold, List<Integer> unmatchedEngine) {
    }

    /**
     * 짝을 짓는다. 같은 이름의 엔진 오퍼레이션이 여럿이면 문서에서 앞의 것부터 쓴다.
     *
     * @param gold   정답 오퍼레이션의 이름표 (문서 순서)
     * @param engine 엔진 오퍼레이션의 이름표 (문서 순서)
     * @return 짝짓기 결과
     */
    static Result pair(List<Key> gold, List<Key> engine) {
        Integer[] partner = new Integer[gold.size()];
        Method[] method = new Method[gold.size()];
        boolean[] taken = new boolean[engine.size()];

        matchBy(gold, engine, Key::name, OperationPairing::normalizeName, Method.NAME, partner, method, taken);
        matchBy(gold, engine, Key::title, OperationPairing::normalizeTitle, Method.TITLE, partner, method, taken);
        int next = 0;
        for (int g = 0; g < gold.size(); g++) {
            if (partner[g] != null) {
                continue;
            }
            while (next < engine.size() && taken[next]) {
                next++;
            }
            if (next == engine.size()) {
                break;
            }
            partner[g] = next;
            method[g] = Method.ORDER;
            taken[next] = true;
        }

        List<Match> matches = new ArrayList<>();
        List<Integer> unmatchedGold = new ArrayList<>();
        for (int g = 0; g < gold.size(); g++) {
            if (partner[g] == null) {
                unmatchedGold.add(g);
            } else {
                matches.add(new Match(g, partner[g], method[g]));
            }
        }
        matches.sort(Comparator.comparingInt(Match::gold));
        List<Integer> unmatchedEngine = new ArrayList<>();
        for (int e = 0; e < engine.size(); e++) {
            if (!taken[e]) {
                unmatchedEngine.add(e);
            }
        }
        return new Result(matches, unmatchedGold, unmatchedEngine);
    }

    private static void matchBy(List<Key> gold, List<Key> engine, Function<Key, String> field,
                                Function<String, String> normalize, Method by,
                                Integer[] partner, Method[] method, boolean[] taken) {
        for (int g = 0; g < gold.size(); g++) {
            String want = normalize.apply(field.apply(gold.get(g)));
            if (partner[g] != null || want.isEmpty()) {
                continue;
            }
            for (int e = 0; e < engine.size(); e++) {
                if (!taken[e] && want.equals(normalize.apply(field.apply(engine.get(e))))) {
                    partner[g] = e;
                    method[g] = by;
                    taken[e] = true;
                    break;
                }
            }
        }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.strip();
    }

    /** 기능명은 칸 폭 때문에 꺾이거나 띄어쓰기가 판마다 달라 공백을 모두 지우고 비교한다 */
    private static String normalizeTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "");
    }
}
