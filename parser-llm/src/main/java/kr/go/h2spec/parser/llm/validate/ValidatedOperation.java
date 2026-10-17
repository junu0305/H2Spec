package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.IrConventions;
import kr.go.h2spec.parser.UrlCandidates;
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;

import java.util.List;

/**
 * 원문 대조·형식 검사를 마친 오퍼레이션 하나. 정규화({@code IrNormalizer})가 이것으로 IR을 만든다.
 * <p>
 * 이름과 주소는 LLM이 옮긴 글이 아니라 원문에서 찾은 칸의 원래 글에서 rule 엔진과 같은 함수로 꺼낸 값이다.
 * 같은 칸에서는 엔진이 달라도 같은 값이 나오게 하기 위해서다.
 *
 * @param position          LLM 결과에서의 순서 (0부터, 문서 순서)
 * @param source            LLM이 돌려준 오퍼레이션 그대로
 * @param operationName     정보 표의 이름 칸에서 확인한 영문 오퍼레이션명. 없거나 확인하지 못했으면 null
 * @param title             국문 기능명 (한 줄로 이은 것). 없으면 빈 문자열
 * @param address           주소 후보와 고른 주소
 * @param requestParameters 검증을 통과한 요청 파라미터 (LLM 순서)
 * @param responseFields    검증을 통과한 응답 필드 (LLM 순서)
 * @param resultCodeField   검증(V8)을 통과한 결과코드 필드 이름(LLM이 적은 그대로, 경로일 수 있다). 없거나 버렸으면 빈 문자열
 * @param successCodes      검증(V8)을 통과한 성공코드 후보 (LLM 순서)
 * @param errorCodes        검증(V9)을 통과한 에러코드
 * @param notes             {@code [검증]} 노트 (출처가 붙은 글)
 * @param removals          검증이 버린 항목 전부. 노트를 남기지 않는 규칙(V8·V9, V12의 같은 이름)도 담는다
 * @param dropReason        오퍼레이션을 건너뛴 이유. 통과했으면 null
 */
public record ValidatedOperation(
        int position,
        LlmOperation source,
        String operationName,
        String title,
        Address address,
        List<Parameter> requestParameters,
        List<Field> responseFields,
        String resultCodeField,
        List<SuccessCode> successCodes,
        List<LlmErrorCode> errorCodes,
        List<String> notes,
        List<Removal> removals,
        String dropReason) {

    public ValidatedOperation {
        // 목록은 바꿀 수 없게 복사해 둔다
        requestParameters = List.copyOf(requestParameters);
        responseFields = List.copyOf(responseFields);
        successCodes = List.copyOf(successCodes);
        errorCodes = List.copyOf(errorCodes);
        notes = List.copyOf(notes);
        removals = List.copyOf(removals);
    }

    /** @return 검증에서 건너뛴 오퍼레이션이면 true */
    public boolean dropped() {
        return dropReason != null;
    }

    /**
     * 알림에 쓰는 이름. rule 엔진의 건너뜀 알림과 같은 순서로 영문 오퍼레이션명, 국문 기능명, 순번을 쓴다.
     *
     * @return {@code getMsrstnList}, {@code 측정소 목록 조회}, {@code 오퍼레이션 2}
     */
    public String label() {
        return label(operationName, title, position);
    }

    /** 검증 중인 오퍼레이션도 같은 이름으로 알린다 */
    static String label(String operationName, String title, int position) {
        if (operationName != null) {
            return operationName;
        }
        return title.isEmpty() ? "오퍼레이션 " + (position + 1) : title;
    }

    /**
     * 주소 후보와 고른 주소. 주소는 LLM이 고르지 않는다. 원문에서 확인한 후보로 rule 엔진과 같은 규칙
     * ({@link IrConventions#endpointUrl}, {@link IrConventions#correctSharedUrls})이 고른다.
     *
     * @param sources         후보마다 원문에서 찾은 칸의 원래 글
     * @param candidates      그 칸에서 rule 엔진과 같은 함수로 꺼낸 후보
     * @param url             고르고 겹치는 주소를 바로잡은 주소. 고를 후보가 없으면 null
     * @param parts           url을 나눈 결과. url이 없으면 null
     * @param selectionNotes  주소를 고르며 남긴 노트 (출처 없는 글, rule 엔진과 같은 문구)
     * @param correctionNotes 겹치는 주소를 바로잡으며 남긴 노트 (출처 없는 글)
     */
    public record Address(
            UrlSources sources,
            UrlCandidates candidates,
            String url,
            IrConventions.UrlParts parts,
            List<String> selectionNotes,
            List<String> correctionNotes) {

        public Address {
            // 목록은 바꿀 수 없게 복사해 둔다
            selectionNotes = List.copyOf(selectionNotes);
            correctionNotes = List.copyOf(correctionNotes);
        }
    }

    /**
     * 주소 후보마다 원문에서 찾은 칸의 원래 글. 원문에서 찾지 못했거나 비어 있던 후보는 null이다.
     *
     * @param callBackUrl       정보 표의 주소 칸
     * @param tableUrl          요청 표 안의 주소 행
     * @param urlLine           "요청 URL" 문단
     * @param requestExampleUrl 요청 예제
     * @param serviceUrl        서비스 개요 표의 서비스 URL
     */
    public record UrlSources(String callBackUrl, String tableUrl, String urlLine, String requestExampleUrl,
                             String serviceUrl) {
    }

    /**
     * 검증을 통과한 요청 파라미터.
     *
     * @param name   원문에서 찾은 자리의 원래 글을 rule 엔진과 같게 다듬은 이름
     * @param source LLM이 돌려준 파라미터 그대로
     */
    public record Parameter(String name, LlmRequestParameter source) {
    }

    /**
     * 검증을 통과한 응답 필드.
     *
     * @param path   각 칸을 원문에서 찾은 자리의 원래 글로 다듬은 경로
     * @param source LLM이 돌려준 필드 그대로
     */
    public record Field(String path, LlmResponseField source) {

        /** @return 경로의 마지막 칸, 곧 필드 이름 ({@code []} 없이) */
        public String leaf() {
            String last = path.substring(path.lastIndexOf('.') + 1);
            return last.endsWith("[]") ? last.substring(0, last.length() - 2) : last;
        }

        /** @return 경로의 첫 칸, 곧 응답의 루트 ({@code []} 없이) */
        public String root() {
            int dot = path.indexOf('.');
            String first = dot < 0 ? path : path.substring(0, dot);
            return first.endsWith("[]") ? first.substring(0, first.length() - 2) : first;
        }
    }

    /**
     * 검증을 통과한 성공코드 후보.
     *
     * @param code     문서 표기 그대로의 코드
     * @param source   코드를 찾은 곳
     * @param position 원문에서 찾은 칸의 문서 순서. 같은 출처의 후보가 엇갈리면 문서에 먼저 나온 것을 쓴다
     */
    public record SuccessCode(String code, LlmSuccessCode.Source source, int position) {
    }

    /**
     * 검증이 버린 항목.
     *
     * @param rule   검사 번호 ({@code V3})
     * @param item   버린 것 (이름, 경로, 코드, 주소 후보)
     * @param reason 버린 이유
     */
    public record Removal(String rule, String item, String reason) {
    }
}
