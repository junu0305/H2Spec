package kr.go.h2spec.eval;

/**
 * 이름 비교의 정밀도·재현율·F1. 오퍼레이션마다의 수를 더해서(micro) 계산한다.
 * 필드가 많은 오퍼레이션이 더 무겁게 들어가지만, 오퍼레이션마다 낸 F1을 평균하면 필드 하나짜리 오퍼레이션이 지표를 흔든다.
 *
 * @param truePositives  양쪽에 있는 이름 수
 * @param falsePositives 엔진에만 있는 이름 수
 * @param falseNegatives 정답에만 있는 이름 수
 */
record Score(int truePositives, int falsePositives, int falseNegatives) {

    /** 아무것도 세지 않은 값 */
    static final Score EMPTY = new Score(0, 0, 0);

    /**
     * 이름 비교 하나를 더한다.
     *
     * @param match 오퍼레이션 하나의 이름 비교
     * @return 더한 값
     */
    Score plus(NameMatch match) {
        return new Score(truePositives + match.truePositives(), falsePositives + match.falsePositives(),
                falseNegatives + match.falseNegatives());
    }

    /** @return 엔진이 낸 이름 중 정답에 있는 비율. 엔진이 낸 이름이 없으면 NaN */
    double precision() {
        return ratio(truePositives, truePositives + falsePositives);
    }

    /** @return 정답 이름 중 엔진이 낸 비율. 정답 이름이 없으면 NaN */
    double recall() {
        return ratio(truePositives, truePositives + falseNegatives);
    }

    /** @return 정밀도와 재현율의 조화평균. 맞은 것이 하나도 없으면 0, 셀 것이 없으면 NaN */
    double f1() {
        if (truePositives + falsePositives + falseNegatives == 0) {
            return Double.NaN;
        }
        return ratio(2.0 * truePositives, 2.0 * truePositives + falsePositives + falseNegatives);
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? Double.NaN : numerator / denominator;
    }
}
