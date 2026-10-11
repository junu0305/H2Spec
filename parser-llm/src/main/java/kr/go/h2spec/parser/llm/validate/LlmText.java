package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.IrText;

/**
 * LLM이 옮긴 글을 다룬다. 직렬화({@code DocumentSerializer})는 칸 안 줄바꿈을 {@code <br>}로, 칸 안의 {@code |}를
 * {@code \|}로 바꿔 보내므로, LLM이 그 표기를 그대로 옮겨 오면 원래 글과 맞지 않는다. 검증과 정규화가 같은 규칙으로 되돌린다.
 */
public final class LlmText {

    private LlmText() {
    }

    /**
     * 직렬화가 바꾼 표기를 원래 글로 되돌린다. null은 빈 문자열로 본다.
     * 중간 스키마는 null을 쓰지 않지만 회사 응답을 믿지 않는다.
     *
     * @param text LLM이 옮긴 글
     * @return {@code <br>}을 줄바꿈으로, {@code \|}를 {@code |}로 되돌린 글
     */
    public static String restore(String text) {
        return text == null ? "" : text.replace("<br>", "\n").replace("\\|", "|");
    }

    /**
     * 되돌린 글을 rule 엔진과 같게 한 줄로 잇는다({@link IrText#oneLine}). 이름·설명이 생성 코드의 한 줄 주석에 들어가기 때문이다.
     *
     * @param text LLM이 옮긴 글
     * @return 한 줄로 이은 글
     */
    public static String oneLine(String text) {
        return IrText.oneLine(restore(text));
    }
}
