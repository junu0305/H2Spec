package kr.go.h2spec.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 평가 도구의 명령행 옵션. llm 엔진은 지금 녹화 응답으로만 돌린다. 회사를 직접 부르는 데 쓸 회사·모델·추론 깊이 옵션
 * (--provider, --model, --effort)은 아직 없다.
 *
 * @param sets      평가할 정답 세트. --proxy만 주면 비어 있다
 * @param engine    추출 엔진 이름
 * @param out       리포트 디렉터리. 주지 않았으면 null (날짜와 엔진으로 정한다)
 * @param proxy     대리 지표를 낼 문서 디렉터리. 주지 않았으면 null
 * @param responses llm 엔진이 LLM 대신 쓸 녹화 응답 디렉터리. rule 엔진이면 null
 * @param root      세트 A를 찾을 저장소 루트
 * @param help      도움말을 보일지
 */
record EvalOptions(List<String> sets, String engine, Path out, Path proxy, Path responses, Path root, boolean help) {

    /** 도움말 */
    static final String USAGE = """
            사용법: llm-eval [--set A] [--engine rule|llm] [--responses <디렉터리>] [--proxy <문서 디렉터리>] [--out <디렉터리>] [--root <저장소 루트>]
              --set        정답 세트. 지금은 A만 있다 (기본: --proxy만 주지 않았으면 A)
              --engine     추출 엔진 rule 또는 llm (기본: rule). llm은 지금 --responses의 녹화 응답으로만 돌린다
              --responses  llm 엔진이 LLM 대신 쓸 녹화 응답 디렉터리. 문서마다 <문서 파일 이름>.json(중간 스키마 JSON)을 둔다
              --proxy      이 디렉터리(하위 포함)의 DOCX/HWP/HWPX로 세트 C 대리 지표(휴리스틱)를 낸다
              --out        리포트 디렉터리 (기본: <저장소 루트>/llm-eval/build/reports/<날짜>-<엔진>)
              --root       세트 A를 찾을 저장소 루트 (기본: 현재 디렉터리)
            LLM이나 외부 서비스를 부르지 않는다.""";

    private static final String SET_A = "A";

    /**
     * 옵션을 읽는다. {@code --set A}와 {@code --set=A} 꼴을 모두 받는다.
     *
     * @param args 명령행 인자
     * @return 옵션
     * @throws IllegalArgumentException 모르는 옵션이거나 값이 없거나 지원하지 않는 값일 때
     */
    static EvalOptions parse(String... args) {
        List<String> sets = null;
        String engine = RuleEvalEngine.NAME;
        Path out = null;
        Path proxy = null;
        Path responses = null;
        Path root = Path.of(".");
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String value = null;
            int equals = name.indexOf('=');
            if (name.startsWith("--") && equals > 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (name.equals("-h") || name.equals("--help")) {
                help = true;
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(name + " 옵션에 값이 없습니다");
                }
                value = args[++i];
            }
            switch (name) {
                case "--set" -> sets = sets(value);
                case "--engine" -> engine = engine(value);
                case "--out" -> out = Path.of(value);
                case "--proxy" -> proxy = Path.of(value);
                case "--responses" -> responses = Path.of(value);
                case "--root" -> root = Path.of(value);
                default -> throw new IllegalArgumentException("알 수 없는 옵션입니다: " + name);
            }
        }
        if (sets == null) {
            sets = proxy == null ? List.of(SET_A) : List.of();
        }
        if (engine.equals(ReplayLlmEvalEngine.NAME) && responses == null && !help) {
            throw new IllegalArgumentException("llm 엔진은 지금 녹화 응답(--responses <디렉터리>)으로만 평가할 수 있습니다. "
                    + "회사를 직접 부르는 평가는 아직 없습니다");
        }
        if (responses != null && !engine.equals(ReplayLlmEvalEngine.NAME)) {
            throw new IllegalArgumentException("--responses는 --engine llm과 함께 씁니다");
        }
        return new EvalOptions(sets, engine, out, proxy, responses, root, help);
    }

    private static List<String> sets(String value) {
        List<String> sets = new ArrayList<>();
        for (String raw : value.split(",")) {
            String set = raw.strip().toUpperCase(Locale.ROOT);
            switch (set) {
                case SET_A -> {
                    if (!sets.contains(set)) {
                        sets.add(set);
                    }
                }
                case "B" -> throw new IllegalArgumentException(
                        "세트 B(docs/sample)는 아직 정답 파일이 없어 평가할 수 없습니다");
                case "C" -> throw new IllegalArgumentException(
                        "세트 C는 정답이 없습니다. --proxy <문서 디렉터리>로 대리 지표를 내세요");
                default -> throw new IllegalArgumentException("알 수 없는 세트입니다: " + raw.strip() + " (A만 있습니다)");
            }
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("--set 옵션에 세트가 없습니다");
        }
        return List.copyOf(sets);
    }

    private static String engine(String value) {
        String engine = value.strip().toLowerCase(Locale.ROOT);
        if (engine.equals(RuleEvalEngine.NAME) || engine.equals(ReplayLlmEvalEngine.NAME)) {
            return engine;
        }
        throw new IllegalArgumentException("알 수 없는 엔진입니다: " + value + " (rule, llm이 있습니다)");
    }
}
