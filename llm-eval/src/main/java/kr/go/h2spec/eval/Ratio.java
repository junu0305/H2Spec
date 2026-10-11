package kr.go.h2spec.eval;

/**
 * 맞은 수와 센 수. 재현율·통과율·정확도를 분자와 분모로 남겨, 비율만 보고는 알 수 없는 표본 크기를 리포트에 같이 적는다.
 *
 * @param hits  맞은 수
 * @param total 센 수
 */
record Ratio(int hits, int total) {

    /** 아무것도 세지 않은 값 */
    static final Ratio EMPTY = new Ratio(0, 0);

    /**
     * 하나를 센다.
     *
     * @param hit 맞았는지
     * @return 더한 값
     */
    Ratio plus(boolean hit) {
        return new Ratio(hits + (hit ? 1 : 0), total + 1);
    }

    /**
     * 다른 값을 더한다.
     *
     * @param other 더할 값
     * @return 더한 값
     */
    Ratio plus(Ratio other) {
        return new Ratio(hits + other.hits, total + other.total);
    }

    /** @return 맞은 비율. 센 것이 없으면 NaN */
    double value() {
        return total == 0 ? Double.NaN : (double) hits / total;
    }
}
