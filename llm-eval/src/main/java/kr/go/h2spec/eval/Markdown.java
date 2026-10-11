package kr.go.h2spec.eval;

import java.util.Locale;

/**
 * 리포트 표를 쓰는 데 필요한 것만 둔 Markdown 도우미. 숫자는 기본 로캘과 상관없이 같은 모양으로 쓴다.
 */
public final class Markdown {

    /** 값이 없는 칸 */
    public static final String NONE = "-";

    private Markdown() {
    }

    /**
     * 표 칸에 넣을 수 있게 고친다. 칸 구분자 {@code |}는 escape하고 줄바꿈은 공백으로 바꾼다.
     *
     * @param text 칸의 글. null이면 빈 칸
     * @return 칸에 넣을 글
     */
    public static String cell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("|", "\\|").replaceAll("\\R+", " ").strip();
    }

    /**
     * 비율을 백분율과 분자·분모로 쓴다 (60.0% (6/10)).
     *
     * @param hits  맞은 수
     * @param total 센 수
     * @return 백분율. 센 것이 없으면 {@link #NONE}
     */
    public static String percent(int hits, int total) {
        if (total == 0) {
            return NONE;
        }
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * hits / total, hits, total);
    }

    /**
     * 0~1 사이의 점수를 소수 셋째 자리까지 쓴다.
     *
     * @param value 점수
     * @return 점수. NaN이면 {@link #NONE}
     */
    public static String decimal(double value) {
        return Double.isNaN(value) ? NONE : String.format(Locale.ROOT, "%.3f", value);
    }
}
