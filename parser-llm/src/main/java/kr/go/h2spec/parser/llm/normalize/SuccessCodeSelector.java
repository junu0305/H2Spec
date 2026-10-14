package kr.go.h2spec.parser.llm.normalize;

import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.SuccessCode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 검증(V8)을 통과한 성공코드 후보에서 {@code errorSpec.successResultCode}를 고른다.
 * <p>
 * 표와 예제가 엇갈리는 문서가 흔하다(기상청 중기예보는 표 {@code 00}·예제 {@code 0}, 선관위 개표소는 표 {@code 00}·예제
 * {@code INFO-00}). 인터셉터는 숫자 코드는 앞자리 0을 떼고 비교하지만 {@code INFO-00}처럼 숫자가 아닌 코드는 글자까지 같아야
 * 성공으로 봐서, 잘못 고르면 정상 응답이 모두 예외가 된다.
 * <ol>
 *   <li>숫자로만 되어 있고 모든 자리가 0인 후보({@code 0}, {@code 00}, {@code 000})는 {@code 00}으로 바꾼다.
 *       {@code 00}은 rule 엔진이 늘 쓰는 값이라 rule보다 나빠지지 않는다.</li>
 *   <li>남은 값이 하나면 그 값이고, 여럿이면 응답 예제 &gt; 응답 필드 표 &gt; 에러코드 표의 정상 행 순으로 고른다.
 *       같은 출처 안에서는 문서에 먼저 나온 것을 쓴다. 선관위는 예제를 따라 {@code INFO-00}이 된다.</li>
 *   <li>바꾸기 전 후보 값이 서로 다르거나 {@code 0}·{@code 000}을 {@code 00}으로 바꿨으면 후보를 모두 노트로 남긴다.
 *       실제 값은 호출해 봐야 확정되므로 검수자가 확인하게 한다.</li>
 *   <li>후보가 없으면 {@code 00}이다. IR은 빈 성공코드를 받지 못한다({@code IrLoader}).</li>
 * </ol>
 */
final class SuccessCodeSelector {

    /** 후보가 없을 때와 모든 자리가 0인 코드의 값 */
    static final String DEFAULT_CODE = "00";

    private static final Pattern ALL_ZEROS = Pattern.compile("0+");
    /** 후보가 엇갈릴 때 고르는 출처의 순서 */
    private static final List<LlmSuccessCode.Source> PRIORITY = List.of(
            LlmSuccessCode.Source.RESPONSE_EXAMPLE, LlmSuccessCode.Source.FIELD_TABLE, LlmSuccessCode.Source.ERROR_TABLE);
    private static final Map<LlmSuccessCode.Source, String> SOURCE_NAMES = Map.of(
            LlmSuccessCode.Source.FIELD_TABLE, "응답 필드 표",
            LlmSuccessCode.Source.RESPONSE_EXAMPLE, "응답 예제",
            LlmSuccessCode.Source.ERROR_TABLE, "에러코드 표");

    private SuccessCodeSelector() {
    }

    /**
     * 고른 성공코드.
     *
     * @param code      {@code successResultCode}에 넣을 값
     * @param note      후보가 엇갈리거나 {@code 0}을 {@code 00}으로 바꿨을 때의 노트 글 (출처 없음). 없으면 null
     * @param defaulted 후보가 없어 기본값을 넣었으면 true
     */
    record Selection(String code, String note, boolean defaulted) {
    }

    static Selection select(List<SuccessCode> candidates) {
        if (candidates.isEmpty()) {
            return new Selection(DEFAULT_CODE, null, true);
        }
        List<String> values = candidates.stream().map(candidate -> normalized(candidate.code())).toList();
        String code;
        if (values.stream().distinct().count() == 1) {
            code = values.get(0);
        } else {
            SuccessCode chosen = candidates.stream()
                    .min(Comparator.comparingInt((SuccessCode candidate) -> PRIORITY.indexOf(candidate.source()))
                            .thenComparingInt(SuccessCode::position))
                    .orElseThrow();
            code = normalized(chosen.code());
        }
        boolean differ = candidates.stream().map(SuccessCode::code).distinct().count() > 1;
        boolean zeroChanged = candidates.stream()
                .anyMatch(candidate -> !candidate.code().equals(normalized(candidate.code())));
        if (!differ && !zeroChanged) {
            return new Selection(code, null, false);
        }
        StringBuilder note = new StringBuilder("성공코드 후보: ")
                .append(candidates.stream()
                        .map(candidate -> candidate.code() + "(" + SOURCE_NAMES.get(candidate.source()) + ")")
                        .collect(Collectors.joining(", ")))
                .append(" → ").append(code);
        if (values.stream().distinct().count() > 1) {
            note.append(" (응답 예제 > 응답 필드 표 > 에러코드 표 순으로 고름)");
        }
        if (zeroChanged) {
            note.append(" (모든 자리가 0인 코드는 00으로 맞춤)");
        }
        return new Selection(code, note.toString(), false);
    }

    private static String normalized(String code) {
        return ALL_ZEROS.matcher(code).matches() ? DEFAULT_CODE : code;
    }
}
