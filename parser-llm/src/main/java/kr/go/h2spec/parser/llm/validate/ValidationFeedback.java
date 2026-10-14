package kr.go.h2spec.parser.llm.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원문 대조에서 버린 것 가운데 LLM이 다시 읽으면 고칠 수 있는 것을 LLM에 돌려줄 글로 적는다.
 * llm 엔진은 이 글이 있으면 한 번 더 부르고, 둘 중 검증을 더 많이 통과한 결과를 쓴다.
 * <p>
 * 원문에 없는 요청 파라미터(V3), 근거와 맞지 않는 응답 경로(V5), 이름 규칙에 맞지 않는 경로(V6), 경로 트리 충돌(V7)과,
 * 오퍼레이션을 통째로 건너뛴 일만 돌려준다. 주소나 apiId가 앞과 같은 뒤 오퍼레이션(V11)은 문서가 그렇게 적은 것이라 빼고,
 * 성공코드·에러코드(V8·V9)와 이름의 겹침(V12)은 결과를 거의 바꾸지 않아 빼서 다시 부르는 일을 줄인다.
 */
public final class ValidationFeedback {

    private static final Set<String> REQUEST_RULES = Set.of("V3");
    private static final Set<String> RESPONSE_RULES = Set.of("V5", "V6", "V7");
    private static final String DUPLICATE_RULE = "V11";
    /** 한 줄에 옮기는 항목 수. 나머지는 개수만 적는다 */
    private static final int MAX_ITEMS = 5;

    private ValidationFeedback() {
    }

    /**
     * @param validation 검증 결과
     * @return 오퍼레이션마다 버린 것을 적은 줄 ({@code - getList: 응답 필드 3개를 버림 — 이유: a, b, c}). 고칠 것이 없으면 빈 목록
     */
    public static List<String> problems(ValidationResult validation) {
        List<String> lines = new ArrayList<>();
        for (ValidatedOperation operation : validation.operations()) {
            if (operation.removals().stream().anyMatch(removal -> DUPLICATE_RULE.equals(removal.rule()))) {
                continue;
            }
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (ValidatedOperation.Removal removal : operation.removals()) {
                String kind = REQUEST_RULES.contains(removal.rule()) ? "요청 파라미터"
                        : RESPONSE_RULES.contains(removal.rule()) ? "응답 필드" : null;
                if (kind != null) {
                    grouped.computeIfAbsent(kind + "\n" + removal.reason(), key -> new ArrayList<>()).add(removal.item());
                }
            }
            grouped.forEach((key, items) -> {
                String[] parts = key.split("\n", 2);
                lines.add("- " + operation.label() + ": " + parts[0] + " " + items.size() + "개를 버림 — " + parts[1]
                        + ": " + items(items));
            });
            if (operation.dropped()) {
                lines.add("- " + operation.label() + ": 오퍼레이션을 건너뜀 — " + operation.dropReason());
            }
        }
        return lines;
    }

    /**
     * 두 검증 결과 가운데 뒤의 것이 더 나은지 본다. 통과한 오퍼레이션이 많은 쪽, 같으면 통과한 요청 파라미터와 응답 필드가 많은 쪽이 낫다.
     *
     * @param first  처음 결과
     * @param second 다시 부른 결과
     * @return 다시 부른 결과가 더 나으면 true. 같으면 처음 결과를 쓰도록 false
     */
    public static boolean better(ValidationResult first, ValidationResult second) {
        long kept = keptOperations(second) - keptOperations(first);
        return kept != 0 ? kept > 0 : keptItems(second) > keptItems(first);
    }

    /**
     * @param validation 검증 결과
     * @return 건너뛰지 않은 오퍼레이션 수
     */
    public static long keptOperations(ValidationResult validation) {
        return validation.operations().stream().filter(operation -> !operation.dropped()).count();
    }

    private static long keptItems(ValidationResult validation) {
        return validation.operations().stream().filter(operation -> !operation.dropped())
                .mapToLong(operation -> operation.requestParameters().size() + operation.responseFields().size())
                .sum();
    }

    private static String items(List<String> items) {
        String shown = String.join(", ", items.subList(0, Math.min(MAX_ITEMS, items.size())));
        return items.size() > MAX_ITEMS ? shown + " 외 " + (items.size() - MAX_ITEMS) + "개" : shown;
    }
}
