package kr.go.h2spec.parser.llm.validate;

/**
 * llm 엔진이 IR {@code metadata.reviewNotes}에 붙이는 출처.
 * 검수자가 노트를 보고 LLM이 적은 것인지, 코드가 고치거나 채운 것인지, rule 엔진과 다른 것인지를 바로 가를 수 있게 한다.
 */
public enum NotePrefix {

    /** LLM이 {@code uncertain}·{@code documentNotes}·{@code unsupported}에 적은 것 */
    LLM("[LLM]"),
    /** 원문 대조·형식 검사에서 제거·대체한 것과 엇갈린 성공코드 후보 */
    VALIDATION("[검증]"),
    /** 정규화가 기본값으로 채우거나 rule 엔진의 규칙으로 고친 것 (주소 규칙이 남긴 노트 포함) */
    NORMALIZATION("[정규화]"),
    /** rule 엔진 결과와의 차이 */
    CROSS_CHECK("[교차검증]");

    private final String label;

    NotePrefix(String label) {
        this.label = label;
    }

    /** @return 노트 앞에 붙이는 표지 ({@code [검증]}) */
    public String label() {
        return label;
    }

    /**
     * 노트 글에 출처를 붙인다.
     *
     * @param text 노트 글
     * @return {@code [출처] 글}
     */
    public String note(String text) {
        return label + " " + text;
    }
}
