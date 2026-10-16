package kr.go.h2spec.parser;

import java.util.List;
import java.util.Objects;

/**
 * 문서 하나를 변환한 결과. 엔진(rule, llm)과 상관없이 같은 꼴이라 CLI 출력과 교차검증·평가가 엔진을 가리지 않고 쓴다.
 *
 * @param operations 찾은 오퍼레이션마다 하나씩 (문서 순서). 변환한 것, 건너뛴 것, 지원 범위 밖으로 건너뛴 것을 모두 담는다.
 *                   교차검증과 평가는 영문 오퍼레이션명, 국문 기능명 순으로 두 엔진의 오퍼레이션을 짝짓고
 *                   남은 것은 이 목록의 순서로 짝짓는다
 * @param skipped    건너뛴 오퍼레이션을 CLI 경고에 쓰는 문구로 ("오퍼레이션 2: 응답 필드 표를 찾지 못했습니다").
 *                   오퍼레이션 기록에서 만들지 않고 엔진이 채운다. 이름표를 붙이는 방식과 순서가 엔진마다 다르기 때문이다.
 *                   rule 엔진은 기존 파서의 skipped()와 같고, IR을 조립하다 실패한 오퍼레이션을 문서를 다 훑은 뒤에 더하므로
 *                   문서 순서와 다를 수 있다
 * @param engine     변환한 엔진의 정보
 */
public record SpecParseResult(List<Operation> operations, List<String> skipped, Engine engine) {

    /** 목록은 바꿀 수 없게 복사해 둔다 */
    public SpecParseResult {
        operations = List.copyOf(operations);
        skipped = List.copyOf(skipped);
        Objects.requireNonNull(engine, "engine");
    }

    /**
     * IR 파일로 쓰고 코드를 생성할 오퍼레이션의 IR을 문서 순서로 돌려준다. 지원 범위 밖으로 건너뛴 오퍼레이션의 IR은
     * 교차검증과 평가에만 쓰고 파일로 쓰지 않으므로 뺀다.
     *
     * @return 변환한 오퍼레이션의 IR. rule 엔진은 기존 파서의 parse 결과와 같다
     */
    public List<ParsedApi> apis() {
        return operations.stream()
                .filter(operation -> operation.api() != null && !operation.outOfScope())
                .map(Operation::api)
                .toList();
    }

    /**
     * 오퍼레이션 하나의 결과. {@link SpecParseResult#apis()}에 들어가는 오퍼레이션만 건너뛴 이유가 없다.
     *
     * @param operationName 정보 표의 영문 오퍼레이션명. 없으면 null
     * @param title         국문 기능명(한 줄로 이은 것). 없으면 null
     * @param api           변환한 IR. 건너뛰었으면 null이고, 지원 범위 밖으로 건너뛰었으면 정규화한 IR
     * @param skipReason    건너뛴 이유. 변환했으면 null
     * @param outOfScope    generator가 받지 못하는 요소(경로의 인증키 등) 때문에 건너뛰었는지.
     *                      llm 엔진만 쓰고 rule 엔진은 늘 false다
     */
    public record Operation(String operationName, String title, ParsedApi api, String skipReason,
                            boolean outOfScope) {

        /** 이유 없이 빠진 오퍼레이션이나 이유가 붙은 채 변환된 오퍼레이션은 경고와 짝짓기를 어긋나게 하므로 받지 않는다 */
        public Operation {
            boolean converted = api != null && !outOfScope;
            if (converted == (skipReason != null)) {
                throw new IllegalArgumentException(converted
                        ? "변환한 오퍼레이션에는 건너뛴 이유가 없어야 합니다: " + skipReason
                        : "건너뛴 오퍼레이션에는 이유가 있어야 합니다");
            }
        }
    }

    /**
     * 변환한 엔진의 정보. 문서마다 어느 엔진·모델로 변환했고 토큰을 얼마나 썼는지 알리는 데 쓴다.
     * 토큰 사용량과 캐시 사용은 같은 결과에서도 실행마다 달라지므로 IR에는 넣지 않는다.
     *
     * @param name       엔진 ("rule", "llm")
     * @param provider   LLM 회사 ID ("claude", "openai", "gemini"). rule 엔진은 null
     * @param model      모델 ID. rule 엔진은 null
     * @param effort     회사별 추론 깊이 설정값. rule 엔진은 null
     * @param cacheUsed  결과 캐시를 썼는지. rule 엔진은 false
     * @param tokenUsage 토큰 사용량. rule 엔진은 {@link TokenUsage#NONE}
     */
    public record Engine(String name, String provider, String model, String effort, boolean cacheUsed,
                         TokenUsage tokenUsage) {

        /** rule 엔진. 회사·모델이 없고 토큰을 쓰지 않는다 */
        public static final Engine RULE = new Engine("rule", null, null, null, false, TokenUsage.NONE);

        /** 이름과 사용량은 늘 있다. 토큰 합계를 낼 때 엔진을 가리지 않고 더할 수 있게 한다 */
        public Engine {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tokenUsage, "tokenUsage");
        }
    }

    /**
     * LLM 토큰 사용량. parser는 parser-llm을 모르므로 회사별 사용량을 이 꼴로 옮겨 담는다.
     * 입력은 캐시와의 관계로 나눠 셋을 더하면 전체 입력이 된다.
     *
     * @param input      캐시에서 읽지도, 캐시에 쓰지도 않은 입력 토큰
     * @param cacheRead  캐시에서 읽은 입력 토큰
     * @param cacheWrite 캐시에 쓴 입력 토큰
     * @param output     출력 단가로 과금되는 출력 토큰 (thinking 포함)
     */
    public record TokenUsage(long input, long cacheRead, long cacheWrite, long output) {

        /** 토큰을 쓰지 않았다 */
        public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);
    }
}
