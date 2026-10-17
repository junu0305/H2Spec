package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.proxy.ResponseExamples.ResponseExample;

import java.util.List;
import java.util.Map;

/**
 * 세트 C의 대리 지표 결과. 원문 블록과 rule 엔진 결과를 패턴으로 비교해 센 휴리스틱이라 정확하지 않다.
 * rule 엔진의 한계마다 해당 문서 수를 세어, llm 엔진이 필요한 근거와 rule 엔진이 아직 못 하는 일(인증 방식, 루트 없는 JSON)의 규모를 보는 데 쓴다.
 *
 * @param directory 문서 디렉터리
 * @param documents 문서마다의 결과 (경로 순)
 */
public record ProxyReport(String directory, List<DocumentSignals> documents) {

    /** 대리 지표 네 가지 */
    public enum Proxy {
        /** (a) generator 확장과 무관하게 rule 엔진의 포맷 판단이 틀리는 문서 (IrAssembler.responseFormat) */
        JSON_EXAMPLE_XML_IR("(a)", "응답 예제는 JSON인데 rule IR의 responseFormat이 XML"),
        /** (b) rule 엔진이 가정하는 표준 응답 구조와 다른 문서. 루트 없는 JSON 지원의 규모 */
        NON_STANDARD_STRUCTURE("(b)", "응답 예제 구조가 표준(response·header·body·items·item)과 다름"),
        /** (c) rule 엔진의 고정 성공코드 00과 다른 문서. 성공코드를 문서에서 읽어야 하는 규모 */
        NON_00_SUCCESS_CODE("(c)", "00이 아닌 정상 코드가 적힘"),
        /** (d) generator가 만들지 못하는 인증 방식. 인증 방식 표현 확장의 규모 */
        NON_SERVICE_KEY_AUTH("(d)", "serviceKey 쿼리가 아닌 인증");

        private final String mark;
        private final String label;

        Proxy(String mark, String label) {
            this.mark = mark;
            this.label = label;
        }

        /** @return 표의 짧은 이름 ((a)~(d)) */
        public String mark() {
            return mark;
        }

        /** @return 지표 설명 */
        public String label() {
            return label;
        }

        /** @return 근거 JSON의 이름 */
        public String jsonKey() {
            return switch (this) {
                case JSON_EXAMPLE_XML_IR -> "jsonExampleXmlIr";
                case NON_STANDARD_STRUCTURE -> "nonStandardStructure";
                case NON_00_SUCCESS_CODE -> "non00SuccessCode";
                case NON_SERVICE_KEY_AUTH -> "nonServiceKeyAuth";
            };
        }
    }

    /**
     * 문서 하나에서 지표 하나의 판정.
     *
     * @param flagged  지표에 걸렸는지
     * @param kinds    세부 종류 (찾은 순서)
     * @param evidence 근거 (블록 번호를 붙인 설명)
     */
    public record Signal(boolean flagged, List<String> kinds, List<String> evidence) {
    }

    /**
     * 문서 하나의 결과.
     *
     * @param document        문서 경로 (디렉터리 기준)
     * @param error           읽지 못한 이유. 읽었으면 null
     * @param operations      rule 엔진이 찾은 오퍼레이션 수
     * @param irs             그중 IR을 낸 수
     * @param examples        찾은 응답 예제 (에러 응답 형식 포함)
     * @param successCodes    찾은 성공코드 후보
     * @param signals         지표마다의 판정. 읽지 못한 문서는 비어 있다
     */
    public record DocumentSignals(String document, String error, int operations, int irs,
                                  List<ResponseExample> examples, List<SuccessCodes.Candidate> successCodes,
                                  Map<Proxy, Signal> signals) {

        /** @return 에러 응답 형식이 아닌 응답 예제 */
        List<ResponseExample> responseExamples() {
            return examples.stream().filter(example -> !example.error()).toList();
        }

        /**
         * 지표에 걸렸는지 본다.
         *
         * @param proxy 지표
         * @return 걸렸으면 true. 읽지 못한 문서는 false
         */
        public boolean flagged(Proxy proxy) {
            Signal signal = signals.get(proxy);
            return signal != null && signal.flagged();
        }
    }

    /** @return 읽은 문서 수. 비율의 분모다 */
    public int readDocuments() {
        return (int) documents.stream().filter(document -> document.error() == null).count();
    }

    /**
     * 지표에 걸린 문서 수.
     *
     * @param proxy 지표
     * @return 문서 수
     */
    public int count(Proxy proxy) {
        return (int) documents.stream().filter(document -> document.flagged(proxy)).count();
    }

    /**
     * 세부 종류가 있는 문서 수.
     *
     * @param proxy 지표
     * @param kind  세부 종류
     * @return 문서 수
     */
    public int count(Proxy proxy, String kind) {
        return (int) documents.stream()
                .filter(document -> document.flagged(proxy) && document.signals().get(proxy).kinds().contains(kind))
                .count();
    }
}
