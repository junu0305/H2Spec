package kr.go.h2spec.generator.emit;

/**
 * IR의 글을 생성 Java 소스의 문자열 리터럴과 주석에 넣을 수 있게 바꾼다.
 *
 * <p>javac는 소스를 토큰으로 나누기 전에 리터럴과 주석 안까지 유니코드 escape(역슬래시 뒤에 u가 오는 꼴)를 먼저 푼다.
 * 그래서 따옴표나 주석 끝을 직접 쓰지 않아도 escape로 리터럴을 닫거나 주석을 끝낼 수 있다.
 * 두 변환 모두 역슬래시를 먼저 다뤄 이 길을 막는다.
 * 한글 같은 비ASCII 글자는 escape하지 않는다. 생성 코드를 그대로 읽을 수 있게 하기 위해서다.
 */
public final class JavaText {

    private JavaText() {
    }

    /**
     * 문자열 리터럴의 따옴표 안에 넣을 글로 바꾼다.
     * 역슬래시를 두 겹으로 만들어 유니코드 escape가 시작되지 못하게 하고, 따옴표와 제어문자를 escape한다.
     * 제어문자를 유니코드 escape로 쓰면 javac가 먼저 풀어 줄바꿈이 리터럴을 끊으므로 8진 escape로 쓴다.
     * null은 "null"이 된다.
     */
    public static String literal(String text) {
        String s = String.valueOf(text);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (Character.isISOControl(c)) {
                        // 세 자리로 써야 뒤에 오는 숫자가 escape에 붙지 않는다
                        sb.append(String.format("\\%03o", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 주석(Javadoc) 안에 넣을 글로 바꾼다.
     * 역슬래시와 주석 끝(별표 뒤 슬래시)을 HTML 문자 참조로 바꿔 주석을 닫지 못하게 한다. Javadoc에서는 원래 글자로 보인다.
     * 줄바꿈은 공백으로 바꿔 한 줄로 둔다. 줄 머리의 별표 없이 이어지는 줄이 생기지 않게 하기 위해서다.
     * null은 "null"이 된다.
     */
    public static String comment(String text) {
        return String.valueOf(text)
                .replace("\\", "&#92;")
                .replace("*/", "*&#47;")
                .replaceAll("[\r\n]+", " ");
    }
}
