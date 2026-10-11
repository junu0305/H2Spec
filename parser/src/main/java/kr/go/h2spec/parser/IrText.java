package kr.go.h2spec.parser;

/**
 * IR에 넣는 문자열을 다듬는다. rule 엔진과 llm 엔진이 같이 쓴다.
 * 생성 코드에 안전하게 넣는 처리(escape, 주석 닫기 무력화)는 IR을 직접 받는 generator가 맡는다.
 */
public final class IrText {

    private IrText() {
    }

    /**
     * 셀 안의 여러 줄을 한 줄로 잇고 앞뒤 공백을 지운다.
     * 이름·설명이 생성 코드의 Javadoc 한 줄 주석에 그대로 들어가기 때문이다.
     *
     * @param text 칸의 글
     * @return 줄바꿈과 그 앞뒤 공백을 공백 하나로 바꾼 글
     */
    public static String oneLine(String text) {
        return text.replaceAll("\\s*\\n\\s*", " ").strip();
    }
}
