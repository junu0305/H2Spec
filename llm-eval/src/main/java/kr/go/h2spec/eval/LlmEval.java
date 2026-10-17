package kr.go.h2spec.eval;

import kr.go.h2spec.eval.DataSets.EvalDocument;
import kr.go.h2spec.eval.proxy.ProxyEvaluator;
import kr.go.h2spec.eval.proxy.ProxyReport;
import kr.go.h2spec.eval.proxy.ProxyReportWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 평가 도구의 진입점. 정답 세트를 엔진으로 돌려 기준선 리포트를 쓰고, 정답 없는 문서 묶음(세트 C)에서는
 * rule 엔진 결과와 원문을 패턴으로 비교한 대리 지표를 낸다. LLM이나 외부 서비스는 부르지 않는다.
 * llm 엔진은 녹화 응답을 재생해 돌린다({@link ReplayLlmEvalEngine}).
 * <pre>{@code
 * ./gradlew :llm-eval:run --args="--set A --engine rule"
 * ./gradlew :llm-eval:run --args="--set A --engine llm --responses <녹화 응답 디렉터리>"
 * ./gradlew :llm-eval:run --args="--proxy <세트 C 디렉터리>"
 * }</pre>
 */
public final class LlmEval {

    /** 기본 리포트 위치 (저장소 루트 기준). 이 아래 {@code <날짜>-<엔진>} 디렉터리에 쓴다 */
    static final String REPORTS = "llm-eval/build/reports";

    private LlmEval() {
    }

    /**
     * 평가를 실행하고 종료 코드로 끝낸다.
     *
     * @param args 명령행 인자 ({@link EvalOptions#USAGE})
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, LocalDate.now()));
    }

    /**
     * 평가를 실행한다.
     *
     * @param args 명령행 인자
     * @param out  진행 상황과 리포트 위치를 쓸 곳
     * @param err  오류를 쓸 곳
     * @param date 리포트에 적을 날짜
     * @return 종료 코드. 0 성공, 1 실행 중 오류, 2 잘못된 옵션
     */
    static int run(String[] args, PrintStream out, PrintStream err, LocalDate date) {
        EvalOptions options;
        try {
            options = EvalOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(EvalOptions.USAGE);
            return 2;
        }
        if (options.help()) {
            out.println(EvalOptions.USAGE);
            return 0;
        }
        // 같은 날 다른 녹화(모델 비교 등)를 돌려도 서로 덮어쓰지 않게 녹화 디렉터리 이름을 붙인다
        Path responsesName = options.responses() == null ? null
                : options.responses().toAbsolutePath().normalize().getFileName();
        String label = responsesName == null ? options.engine() : options.engine() + "-" + responsesName;
        Path outDir = options.out() != null
                ? options.out()
                : options.root().resolve(REPORTS).resolve(date + "-" + label);
        try {
            if (!options.sets().isEmpty()) {
                baseline(options, outDir, date, out);
            }
            if (options.proxy() != null) {
                proxy(options.proxy(), outDir, out);
            }
            return 0;
        } catch (IOException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static void baseline(EvalOptions options, Path outDir, LocalDate date, PrintStream out)
            throws IOException {
        // 여러 세트를 주면 한 리포트로 합쳐 잰다. llm 엔진을 README에서 "검증됨"으로 올릴지 정하는 기준이 세트 A·B를 합쳐서 보기 때문이다
        List<EvalDocument> documents = new ArrayList<>();
        for (String set : options.sets()) {
            documents.addAll(DataSets.documents(set, options.root()));
        }
        String set = String.join(",", options.sets());
        out.println("세트 " + set + ": 문서 " + documents.size() + "개를 " + options.engine() + " 엔진으로 평가합니다");
        EvalEngine engine = new RuleEvalEngine();
        if (options.responses() != null) {
            out.println("녹화 응답: " + options.responses());
            ReplayLlmEvalEngine replay = new ReplayLlmEvalEngine(options.responses(),
                    warning -> out.println("    경고: " + warning),
                    documents.stream().map(EvalDocument::document).toList());
            long recorded = documents.stream().filter(document -> replay.recording(document.document()).isPresent()).count();
            if (recorded == 0) {
                // 이름이 맞지 않는 디렉터리를 주면 모든 문서가 지표에서 빠진 빈 리포트가 성공으로 끝난다
                throw new IllegalStateException("녹화 응답 디렉터리에 평가할 문서의 응답이 하나도 없습니다: " + options.responses()
                        + " (문서마다 <문서 파일 이름>.json 또는 <문서 이름>.json)");
            }
            if (recorded < documents.size()) {
                out.println("녹화 응답이 없는 문서 " + (documents.size() - recorded) + "개는 지표에서 뺍니다");
            }
            engine = replay;
        }
        BaselineEvaluator evaluator = new BaselineEvaluator(engine, new GeneratorCheck());
        BaselineReport report = evaluator.evaluate(set, documents, options.root(), date,
                line -> out.println("  " + line));
        List<Path> written = new BaselineReportWriter().write(report, outDir);
        out.println("요약: " + written.get(0));
        out.println("문서별 차이: " + outDir.resolve(BaselineReportWriter.DOCUMENTS));
    }

    private static void proxy(Path directory, Path outDir, PrintStream out) throws IOException {
        out.println("대리 지표(휴리스틱): " + directory);
        ProxyReport report = new ProxyEvaluator().evaluate(directory, line -> out.println("  " + line));
        List<Path> written = new ProxyReportWriter().write(report, outDir);
        out.println("대리 지표 요약: " + written.get(0));
        out.println("대리 지표 근거: " + written.get(1));
    }
}
