package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.eval.GoldFile.GoldField;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.GoldFile.GoldParameter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 정답 파일에 앞으로 더할 키로 재는 지표. 키가 없는 오퍼레이션은 그 지표에서 빠지고,
 * 정답 오퍼레이션에 키가 하나도 없으면 리포트에 "정답 없음"으로 남는다.
 * 지금 corpus 정답에는 이 키가 없어 모두 꺼져 있다. 값을 짐작해 채우지 않는다.
 * <p>
 * 짝을 짓고 엔진이 IR을 낸 오퍼레이션만 잰다. 못 찾은 오퍼레이션은 재현율에서 이미 센다.
 */
enum DetailMetric {

    /** 요청 파라미터 필수 여부. 이름이 맞은 파라미터만 본다 (이름은 요청 파라미터 F1이 본다) */
    REQUEST_REQUIRED("요청 필수 여부 정확도", "requestDetail", gold -> gold.requestDetail() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            Map<String, Boolean> actual = new HashMap<>();
            for (JsonNode param : api(engine).path("requestParameters")) {
                actual.putIfAbsent(param.path("name").asText(), param.path("required").asBoolean());
            }
            int compared = 0;
            List<String> mismatches = new ArrayList<>();
            for (GoldParameter want : gold.requestDetail()) {
                Boolean got = actual.get(want.name());
                if (want.required() == null || got == null) {
                    continue;
                }
                compared++;
                if (!want.required().equals(got)) {
                    mismatches.add(want.name() + ": 정답 " + required(want.required()) + ", IR " + required(got));
                }
            }
            return new Check(compared, compared - mismatches.size(), mismatches);
        }
    },

    /** 응답 필드의 전체 경로. rule 엔진은 표준 구조를 가정해 경로를 만들므로 llm 엔진과 견줄 핵심 지표다 */
    RESPONSE_PATH("응답 경로 정확도", "responseDetail", gold -> gold.responseDetail() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            Set<String> actual = new HashSet<>(paths(engine));
            List<String> missing = new ArrayList<>();
            for (GoldField want : gold.responseDetail()) {
                if (!actual.contains(want.path())) {
                    missing.add("IR에 없음: " + want.path());
                }
            }
            int compared = gold.responseDetail().size();
            return new Check(compared, compared - missing.size(), missing);
        }
    },

    /** 응답 필드 타입. 경로가 맞은 필드만 본다 */
    RESPONSE_TYPE("응답 타입 정확도", "responseDetail", gold -> gold.responseDetail() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            Map<String, String> actual = new HashMap<>();
            for (JsonNode field : api(engine).path("responseFields")) {
                actual.putIfAbsent(field.path("path").asText(), field.path("type").asText());
            }
            int compared = 0;
            List<String> mismatches = new ArrayList<>();
            for (GoldField want : gold.responseDetail()) {
                String got = actual.get(want.path());
                if (want.type() == null || got == null) {
                    continue;
                }
                compared++;
                if (!want.type().equalsIgnoreCase(got)) {
                    mismatches.add(want.path() + ": 정답 " + want.type() + ", IR " + got);
                }
            }
            return new Check(compared, compared - mismatches.size(), mismatches);
        }
    },

    /**
     * 응답 포맷. 정답이 XML·JSON일 때만 잰다. IR에는 둘 중 하나만 들어가는데, BOTH 같은 값은 기본 포맷 없이는
     * 맞는 IR 값을 정할 수 없다
     */
    RESPONSE_FORMAT("응답 포맷 정확도", "responseFormat", gold -> gold.responseFormat() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            String want = gold.responseFormat().toUpperCase(Locale.ROOT);
            if (!want.equals("XML") && !want.equals("JSON")) {
                return null;
            }
            String got = api(engine).path("responseFormat").asText();
            return single(want.equalsIgnoreCase(got), "정답 " + want + ", IR " + got);
        }
    },

    /**
     * 성공코드. 정답이 빈 값이면 문서에 성공코드가 없다는 뜻이고, IR의 00과 그 사정을 적은 노트가 함께 있어야 맞다.
     * IR은 빈 성공코드를 받지 못한다
     */
    SUCCESS_CODE("성공코드 정확도", "successCode", gold -> gold.successCode() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            String got = api(engine).path("errorSpec").path("successResultCode").asText();
            if (gold.successCode().isEmpty()) {
                boolean noted = hasSuccessCodeNote(engine);
                return single(DEFAULT_SUCCESS_CODE.equals(got) && noted,
                        "정답 없음(00 + 노트), IR " + got + (noted ? " + 노트" : ", 노트 없음"));
            }
            return single(gold.successCode().equals(got), "정답 " + gold.successCode() + ", IR " + got);
        }
    },

    /**
     * 성공코드 후보가 엇갈린 문서에서 노트를 남겼는지. llm 엔진의 성공코드 정책({@code SuccessCodeSelector})은
     * 후보 값이 서로 다르거나 0·000을 00으로 바꿨을 때 후보를 모두 노트로 남긴다. 실제 값은 호출해 봐야 알아 검수자가 확인해야 하기 때문이다
     */
    SUCCESS_CODE_NOTE("성공코드 엇갈림 노트", "successCodeCandidates", gold -> gold.successCodeCandidates() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            if (!needsSuccessCodeNote(gold.successCodeCandidates())) {
                return null;
            }
            return single(hasSuccessCodeNote(engine), "후보 " + gold.successCodeCandidates() + "인데 성공코드 노트가 없음");
        }
    },

    /** 인증 방식 */
    AUTH("인증 방식 정확도", "auth", gold -> gold.auth() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            String got = authKind(api(engine));
            return single(gold.auth().equalsIgnoreCase(got), "정답 " + gold.auth() + ", IR " + got);
        }
    },

    /**
     * 지원 범위 밖 판정. supportedByGenerator가 false인 오퍼레이션은 건너뛰고 true인 것은 건너뛰지 않아야 맞다.
     * rule 엔진은 범위를 판정하지 않으므로 false인 오퍼레이션에서 늘 틀린다
     */
    SCOPE("지원 범위 밖 판정 정확도", "supportedByGenerator", gold -> gold.supportedByGenerator() != null) {
        @Override
        Check check(GoldOperation gold, EngineOperation engine) {
            boolean supported = gold.supportedByGenerator();
            return single(supported != engine.outOfScope(),
                    supported ? "지원 범위 안인데 범위 밖으로 건너뜀" : "지원 범위 밖인데 건너뛰지 않음");
        }
    };

    /** 문서가 성공코드를 알려 주지 않을 때 IR이 쓰는 값 */
    static final String DEFAULT_SUCCESS_CODE = "00";
    /** 모든 자리가 0인 숫자 코드. 정책은 이것을 00으로 바꾼다 */
    private static final String ALL_ZERO = "0+";

    private final String label;
    private final String goldKey;
    private final Predicate<GoldOperation> hasKey;

    DetailMetric(String label, String goldKey, Predicate<GoldOperation> hasKey) {
        this.label = label;
        this.goldKey = goldKey;
        this.hasKey = hasKey;
    }

    /**
     * 짝 하나를 잰다.
     *
     * @param gold   정답 오퍼레이션. 이 지표의 키가 있다
     * @param engine 짝지은 엔진 오퍼레이션. IR이 있다
     * @return 잰 결과. 정답 값으로는 잴 수 없으면 null
     */
    abstract Check check(GoldOperation gold, EngineOperation engine);

    /** @return 리포트에 쓰는 지표 이름 */
    String label() {
        return label;
    }

    /** @return 이 지표가 쓰는 정답 파일의 키 */
    String goldKey() {
        return goldKey;
    }

    /** @return 문서별 차이 JSON에서 쓰는 이름 (REQUEST_REQUIRED → requestRequired) */
    String jsonKey() {
        StringBuilder key = new StringBuilder();
        for (String word : name().toLowerCase(Locale.ROOT).split("_")) {
            key.append(key.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return key.toString();
    }

    /**
     * 정답 오퍼레이션에 이 지표의 키가 있는지 본다.
     *
     * @param gold 정답 오퍼레이션
     * @return 키가 있으면 true
     */
    boolean hasKey(GoldOperation gold) {
        return hasKey.test(gold);
    }

    /**
     * 짝 하나에서 잴 수 있는 지표를 모두 잰다.
     *
     * @param gold   정답 오퍼레이션
     * @param engine 짝지은 엔진 오퍼레이션. IR이 있어야 한다
     * @return 지표마다의 결과. 키가 없거나 잴 수 없는 지표는 빠진다
     */
    static Map<DetailMetric, Check> checkAll(GoldOperation gold, EngineOperation engine) {
        Map<DetailMetric, Check> checks = new EnumMap<>(DetailMetric.class);
        for (DetailMetric metric : values()) {
            if (metric.hasKey(gold)) {
                Check check = metric.check(gold, engine);
                if (check != null) {
                    checks.put(metric, check);
                }
            }
        }
        return checks;
    }

    /**
     * 성공코드 후보가 노트를 남겨야 하는 모양인지 본다: 값이 서로 다르거나, 00이 아닌 0·000 같은 값이 있다.
     *
     * @param candidates 후보 값
     * @return 노트를 남겨야 하면 true
     */
    static boolean needsSuccessCodeNote(List<String> candidates) {
        boolean renamed = candidates.stream()
                .anyMatch(code -> code.matches(ALL_ZERO) && !DEFAULT_SUCCESS_CODE.equals(code));
        return renamed || new HashSet<>(candidates).size() > 1;
    }

    /**
     * IR의 인증 방식. IR에 {@code api.auth}가 생기면 그 종류를, 없으면 {@code authType}을 정답과 같은 이름으로 바꿔 쓴다.
     * rule 엔진 IR은 늘 SERVICE_KEY_QUERY_PARAM이고 api.auth가 없다. llm 엔진은 serviceKey 쿼리가 아닌 키를 api.auth에 적고,
     * 지원 범위 밖으로 둔 오퍼레이션은 authType에 인증 종류(OTHER 등)를 적는다.
     *
     * @param api IR의 api 노드
     * @return SERVICE_KEY_QUERY 같은 인증 종류. 적혀 있지 않으면 UNKNOWN
     */
    static String authKind(JsonNode api) {
        JsonNode auth = api.path("auth");
        if (auth.isObject() && auth.hasNonNull("kind")) {
            return auth.get("kind").asText();
        }
        if (auth.isTextual()) {
            return auth.asText();
        }
        String authType = api.path("authType").asText("");
        if (authType.isEmpty()) {
            return "UNKNOWN";
        }
        return "SERVICE_KEY_QUERY_PARAM".equals(authType) ? "SERVICE_KEY_QUERY" : authType;
    }

    /**
     * IR이 성공코드를 검수하라는 노트를 남겼는지 본다. 노트 문구는 엔진마다 다르므로 "성공코드"라는 말이 든 노트를 찾는다.
     *
     * @param engine 엔진 오퍼레이션
     * @return 그런 노트가 있으면 true
     */
    static boolean hasSuccessCodeNote(EngineOperation engine) {
        for (JsonNode note : engine.ir().path("metadata").path("reviewNotes")) {
            // 성공코드 정책이 남기는 노트([검증] 엇갈린 후보, [정규화] 찾지 못해 00)만 센다. 교차검증 노트(rule 엔진과 다름)는 이 지표가 보려는
            // 것이 아니다
            String text = note.asText();
            if ((text.startsWith("[검증] ") || text.startsWith("[정규화] ")) && text.contains("성공코드")) {
                return true;
            }
        }
        return false;
    }

    /**
     * IR의 응답 필드 경로.
     *
     * @param engine 엔진 오퍼레이션
     * @return 경로 (IR 순서)
     */
    static List<String> paths(EngineOperation engine) {
        List<String> paths = new ArrayList<>();
        api(engine).path("responseFields").forEach(field -> paths.add(field.path("path").asText()));
        return paths;
    }

    private static JsonNode api(EngineOperation engine) {
        return engine.ir().path("api");
    }

    private static String required(boolean required) {
        return required ? "필수" : "선택";
    }

    private static Check single(boolean correct, String mismatch) {
        return new Check(1, correct ? 1 : 0, correct ? List.of() : List.of(mismatch));
    }

    /**
     * 짝 하나에서 잰 결과.
     *
     * @param compared   비교한 수
     * @param correct    맞은 수
     * @param mismatches 틀린 것의 설명
     */
    record Check(int compared, int correct, List<String> mismatches) {

        /** @return 비율로 더할 수 있는 값 */
        Ratio ratio() {
            return new Ratio(correct, compared);
        }
    }
}
