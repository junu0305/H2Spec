package kr.go.h2spec.parser.llm.crosscheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.llm.normalize.NormalizedOperation;
import kr.go.h2spec.parser.llm.validate.NotePrefix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * llm 엔진 결과를 같은 Block 목록으로 돌린 rule 엔진 결과와 비교한다. 사람이 볼 신호를 만드는 단계라
 * LLM 결과를 바꾸지 않는다. 차이는 IR에 {@code [교차검증]} 노트로만 남기고, 자동으로 합치지 않는다.
 * 한 문서에 두 엔진의 결과가 섞이면 검수가 어려워지기 때문이다.
 * <p>
 * 오퍼레이션은 영문 오퍼레이션명, 국문 기능명 순으로 짝짓고 남은 것은 문서 안 순서로 짝짓는다. 주소로 짝짓지 않는다.
 * 주소가 틀리면 짝이 지어지지 않아 정작 주소 차이를 놓치기 때문이다. 지원 범위 밖으로 건너뛴 llm 오퍼레이션도 정규화한 IR이 있으므로
 * 짝을 지어, "rule 엔진만 찾은 오퍼레이션"으로 잘못 경고하지 않는다. 검증에서 건너뛰어 IR이 없는 llm 오퍼레이션은 이름·기능명이 같을
 * 때만 짝을 짓는다. 순서로만 짝지으면 LLM이 지어낸 오퍼레이션이 rule 엔진의 오퍼레이션을 가져가 놓친 오퍼레이션을 알리지 못한다.
 */
public final class CrossChecker {

    /** rule 엔진과 짝이 없는 llm 오퍼레이션에 남기는 노트 */
    public static final String NOT_FOUND_BY_RULE = "rule 엔진은 찾지 못한 오퍼레이션";

    /** 짝을 지은 기준 */
    public enum PairingKey {
        /** 영문 오퍼레이션명 */
        OPERATION_NAME,
        /** 국문 기능명 (공백 무시) */
        TITLE,
        /** 이름으로 짝짓고 남은 것의 문서 안 순서 */
        ORDER
    }

    /**
     * 짝지은 두 오퍼레이션.
     *
     * @param llmIndex  llm 오퍼레이션 목록에서의 자리
     * @param ruleIndex rule 엔진 {@code RuleEngine.Result.operations()}에서의 자리
     * @param key       짝을 지은 기준
     */
    public record Pair(int llmIndex, int ruleIndex, PairingKey key) {
    }

    /**
     * llm 엔진 결과를 rule 엔진 결과와 비교한다.
     *
     * @param llmOperations 정규화한 llm 오퍼레이션 (문서 순서). 지원 범위 밖·검증에서 건너뛴 것도 담는다
     * @param rule          같은 Block 목록으로 돌린 rule 엔진 결과
     * @return 노트를 더한 llm 오퍼레이션, 짝, rule 엔진만 찾은 오퍼레이션의 경고
     */
    public CrossCheckResult check(List<NormalizedOperation> llmOperations, RuleEngine.Result rule) {
        List<RuleEngine.OperationResult> ruleOperations = rule.operations();
        List<Pair> pairs = pair(llmOperations, ruleOperations);
        Map<Integer, Pair> byLlm = new HashMap<>();
        Set<Integer> pairedRule = new HashSet<>();
        for (Pair pair : pairs) {
            byLlm.put(pair.llmIndex(), pair);
            pairedRule.add(pair.ruleIndex());
        }
        List<NormalizedOperation> checked = new ArrayList<>();
        for (int i = 0; i < llmOperations.size(); i++) {
            NormalizedOperation operation = llmOperations.get(i);
            Pair pair = byLlm.get(i);
            List<String> differences;
            if (operation.ir() == null) {
                // 검증에서 건너뛴 오퍼레이션은 노트를 붙일 IR이 없다. 건너뛴 이유는 따로 알린다
                differences = List.of();
            } else if (pair == null) {
                differences = List.of(NOT_FOUND_BY_RULE);
            } else {
                RuleEngine.OperationResult found = ruleOperations.get(pair.ruleIndex());
                differences = found.api() == null
                        ? List.of("rule 엔진은 이 오퍼레이션을 건너뜀: " + found.skipReason())
                        : differences(operation.ir(), found.api().ir(), operation.formatSelectable());
            }
            checked.add(differences.isEmpty() ? operation : withNotes(operation, differences));
        }
        List<String> warnings = new ArrayList<>();
        for (int r = 0; r < ruleOperations.size(); r++) {
            if (!pairedRule.contains(r)) {
                warnings.add(ruleOnlyWarning(ruleOperations.get(r), r));
            }
        }
        return new CrossCheckResult(checked, pairs, warnings);
    }

    /**
     * 두 엔진의 오퍼레이션을 짝짓는다. 영문 오퍼레이션명, 국문 기능명 순으로 짝짓고 남은 것은 문서 안 순서로 짝짓는다.
     *
     * @param llmOperations  llm 오퍼레이션 (문서 순서)
     * @param ruleOperations rule 엔진의 오퍼레이션 기록 (문서 순서)
     * @return 짝 (llm 오퍼레이션 순서)
     */
    public static List<Pair> pair(List<NormalizedOperation> llmOperations,
                                  List<RuleEngine.OperationResult> ruleOperations) {
        boolean[] llmPaired = new boolean[llmOperations.size()];
        boolean[] rulePaired = new boolean[ruleOperations.size()];
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < llmOperations.size(); i++) {
            String name = llmOperations.get(i).operationName();
            if (name == null || name.isBlank()) {
                continue;
            }
            for (int r = 0; r < ruleOperations.size(); r++) {
                if (!rulePaired[r] && name.equals(ruleOperations.get(r).operationName())) {
                    llmPaired[i] = rulePaired[r] = true;
                    pairs.add(new Pair(i, r, PairingKey.OPERATION_NAME));
                    break;
                }
            }
        }
        for (int i = 0; i < llmOperations.size(); i++) {
            String title = titleKey(llmOperations.get(i).title());
            if (llmPaired[i] || title.isEmpty()) {
                continue;
            }
            for (int r = 0; r < ruleOperations.size(); r++) {
                if (!rulePaired[r] && title.equals(titleKey(ruleOperations.get(r).title()))) {
                    llmPaired[i] = rulePaired[r] = true;
                    pairs.add(new Pair(i, r, PairingKey.TITLE));
                    break;
                }
            }
        }
        // 순서로는 같은 오퍼레이션이라는 근거가 약하므로, 검증에서 건너뛰어 IR이 없는 llm 오퍼레이션(지어낸 구간일 수 있다)과 영문명이 서로 다른
        // 두 오퍼레이션은 짝짓지 않는다. 짝지으면 LLM이 놓친 오퍼레이션의 경고가 사라지거나 엉뚱한 차이를 알린다. 다만 그 자리의 rule
        // 오퍼레이션은 건너뛰어 뒤의 오퍼레이션끼리 순서가 어긋나지 않게 한다. 건너뛴 rule 오퍼레이션은 짝이 없어 경고로 알린다
        int r = 0;
        for (int i = 0; i < llmOperations.size(); i++) {
            if (llmPaired[i]) {
                continue;
            }
            while (r < ruleOperations.size() && rulePaired[r]) {
                r++;
            }
            if (r == ruleOperations.size()) {
                break;
            }
            NormalizedOperation operation = llmOperations.get(i);
            int position = r++;
            if (operation.ir() == null || differentNames(operation.operationName(), ruleOperations.get(position).operationName())) {
                continue;
            }
            rulePaired[position] = true;
            pairs.add(new Pair(i, position, PairingKey.ORDER));
        }
        pairs.sort(Comparator.comparingInt(Pair::llmIndex));
        return pairs;
    }

    /**
     * 짝지은 두 IR의 차이. 주소, 요청 파라미터 이름 집합, 응답 필드 이름 집합, 응답 포맷, 성공코드를 본다.
     * 응답 경로 구조는 rule 엔진이 표준 구조를 강제하므로 비교하지 않는다.
     * 응답 형식을 고를 수 있는 오퍼레이션이면 형식 차이를 알리지 않는다. 문서가 두 형식을 다 내고 생성 클라이언트가
     * 포맷 파라미터로 IR의 형식을 골라 부르면 어느 엔진의 형식이든 동작해서, 그 차이는 검수할 거리가 아니다.
     *
     * @param llmIr            llm 엔진의 IR
     * @param ruleIr           rule 엔진의 IR
     * @param formatSelectable 응답 형식을 고를 수 있는 오퍼레이션인지 ({@link NormalizedOperation#formatSelectable()})
     * @return 차이마다 한 줄 (출처 없는 글). 같으면 빈 목록
     */
    public static List<String> differences(ObjectNode llmIr, ObjectNode ruleIr, boolean formatSelectable) {
        JsonNode llm = llmIr.path("api");
        JsonNode rule = ruleIr.path("api");
        List<String> differences = new ArrayList<>();
        // 끝 슬래시만 무시하고 글자 그대로 본다. 스킴을 무시하면(sameUrl) http와 https가 갈린 경우를 놓친다
        String llmUrl = url(llm);
        String ruleUrl = url(rule);
        if (!llmUrl.equals(ruleUrl)) {
            differences.add("주소가 rule 엔진과 다름: rule " + ruleUrl + ", llm " + llmUrl);
        }
        String parameters = setDifference(names(rule.path("requestParameters")), names(llm.path("requestParameters")));
        if (parameters != null) {
            differences.add("요청 파라미터가 rule 엔진과 다름: " + parameters);
        }
        String fields = setDifference(leafNames(rule.path("responseFields")), leafNames(llm.path("responseFields")));
        if (fields != null) {
            differences.add("응답 필드가 rule 엔진과 다름: " + fields);
        }
        String llmFormat = llm.path("responseFormat").asText();
        String ruleFormat = rule.path("responseFormat").asText();
        if (!llmFormat.equals(ruleFormat) && !formatSelectable) {
            differences.add("응답 형식이 rule 엔진과 다름: rule " + ruleFormat + ", llm " + llmFormat);
        }
        String llmCode = llm.path("errorSpec").path("successResultCode").asText();
        String ruleCode = rule.path("errorSpec").path("successResultCode").asText();
        if (!llmCode.equals(ruleCode)) {
            differences.add("성공코드가 rule 엔진과 다름: rule " + ruleCode + ", llm " + llmCode);
        }
        return differences;
    }

    /** 노트를 더한 사본을 만든다. 받은 IR은 고치지 않는다 */
    private static NormalizedOperation withNotes(NormalizedOperation operation, List<String> differences) {
        ObjectNode ir = operation.ir().deepCopy();
        JsonNode existingMetadata = ir.get("metadata");
        ObjectNode metadata = existingMetadata instanceof ObjectNode object ? object : ir.putObject("metadata");
        JsonNode existingNotes = metadata.get("reviewNotes");
        ArrayNode notes = existingNotes instanceof ArrayNode array ? array : metadata.putArray("reviewNotes");
        differences.forEach(difference -> notes.add(NotePrefix.CROSS_CHECK.note(difference)));
        // rule 엔진과 같이 노트가 하나라도 있으면 검수 대상이다
        metadata.put("manualReviewRequired", true);
        return new NormalizedOperation(operation.operationName(), operation.title(), operation.apiId(), ir,
                operation.skipReason(), operation.outOfScope(), operation.formatSelectable());
    }

    private static String ruleOnlyWarning(RuleEngine.OperationResult operation, int index) {
        String label = operation.operationName() != null ? operation.operationName()
                : operation.title() != null ? operation.title()
                : "오퍼레이션 " + (index + 1);
        String detail = operation.api() != null
                ? url(operation.api().ir().path("api"))
                : "rule 엔진도 건너뜀: " + operation.skipReason();
        return "rule 엔진만 찾은 오퍼레이션: " + label + " (" + detail + ")";
    }

    private static String url(JsonNode api) {
        return (api.path("baseUrl").asText() + api.path("endpoint").asText()).replaceAll("/+$", "");
    }

    /** 두 엔진 모두 영문 오퍼레이션명을 찾았는데 서로 다르면 다른 오퍼레이션이다 */
    private static boolean differentNames(String llmName, String ruleName) {
        return llmName != null && !llmName.isBlank() && ruleName != null && !ruleName.isBlank() && !llmName.equals(ruleName);
    }

    /** 국문 기능명은 띄어쓰기가 칸마다 달라("공휴일 정보조회", "공휴일 정보 조회") 공백을 빼고 비교한다 */
    private static String titleKey(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "");
    }

    private static Set<String> names(JsonNode parameters) {
        Set<String> names = new LinkedHashSet<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));
        return names;
    }

    /** 응답 필드 경로의 마지막 칸. rule 엔진은 경로 구조를 표준으로 가정하므로 이름만 견준다 */
    private static Set<String> leafNames(JsonNode fields) {
        Set<String> names = new LinkedHashSet<>();
        fields.forEach(field -> {
            String path = field.path("path").asText();
            String leaf = path.substring(path.lastIndexOf('.') + 1);
            names.add(leaf.endsWith("[]") ? leaf.substring(0, leaf.length() - 2) : leaf);
        });
        return names;
    }

    /** @return 두 집합의 차이를 적은 글. 같으면 null */
    private static String setDifference(Set<String> rule, Set<String> llm) {
        List<String> ruleOnly = rule.stream().filter(name -> !llm.contains(name)).toList();
        List<String> llmOnly = llm.stream().filter(name -> !rule.contains(name)).toList();
        if (ruleOnly.isEmpty() && llmOnly.isEmpty()) {
            return null;
        }
        List<String> sides = new ArrayList<>();
        if (!ruleOnly.isEmpty()) {
            sides.add("rule에만 " + String.join(", ", ruleOnly));
        }
        if (!llmOnly.isEmpty()) {
            sides.add("llm에만 " + String.join(", ", llmOnly));
        }
        return String.join(" / ", sides);
    }
}
