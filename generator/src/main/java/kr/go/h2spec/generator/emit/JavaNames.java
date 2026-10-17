package kr.go.h2spec.generator.emit;

import javax.lang.model.SourceVersion;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IR의 파라미터/경로 이름을 Java 식별자 규칙으로 변환한다.
 *
 * <p>구분자(_, -, 공백) 말고도 식별자에 못 쓰는 글자({@code cond[country_nm::EQ]}의 {@code [ : ]}, {@code item.name}의 {@code .})가
 * 남으면 그 글자도 구분자로 보고 다시 잇는다({@code condCountryNmEq}, {@code itemName}). 그런 글자가 없는 이름은 예전과 같은 이름이 된다.
 * 예약어와 숫자로 시작하는 이름도 식별자가 되게 고친다({@code abstract} → {@code abstract_}, {@code 1st} → {@code _1st}).
 * 원래 이름은 생성 코드의 어노테이션과 질의 문자열에 그대로 남는다.
 */
public final class JavaNames {

    private static final Pattern SEPARATOR = Pattern.compile("[_\\-\\s]+");

    private JavaNames() {
    }

    /** 구분자(_,-,공백)가 있으면 camelCase로 합치고, 없으면 첫 글자만 소문자로 만든다. 식별자에 못 쓰는 글자도 구분자로 본다 */
    public static String camel(String name) {
        String joined = joinCamel(name);
        return avoidReserved(hasNonIdentifierChar(joined) ? joinCamel(separateNonIdentifierChars(name)) : joined);
    }

    /**
     * 예약어({@code abstract}, {@code default}, {@code new})면 뒤에 _를, 숫자로 시작하면 앞에 _를 붙여 식별자로 만든다.
     * 원래 이름은 어노테이션과 질의 문자열에 남는다. 식별자로 쓸 수 있던 이름은 바뀌지 않는다
     */
    private static String avoidReserved(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }
        return SourceVersion.isKeyword(name) ? name + "_" : name;
    }

    /** 구분자가 있으면 PascalCase로 합치고, 없으면 첫 글자만 대문자로 만든다. 식별자에 못 쓰는 글자도 구분자로 본다 */
    public static String pascal(String name) {
        String joined = joinPascal(name);
        return hasNonIdentifierChar(joined) ? joinPascal(separateNonIdentifierChars(name)) : joined;
    }

    /**
     * {@link #camel}과 같되 식별자에 못 쓰는 글자를 구분자로 보지 않고 남긴다. 엔드포인트로 메서드 이름을 만들 때 쓴다.
     * 그 이름은 남은 글자를 _로 바꿔 왔다("/getMsrstnList.do" → getMsrstnList_do). 이미 생성한 메서드 이름이 바뀌지 않게 따로 둔다
     */
    static String camelKeepingSymbols(String name) {
        return joinCamel(name);
    }

    private static String joinCamel(String name) {
        if (name.isBlank()) {
            return "";
        }
        String[] parts = SEPARATOR.split(name);
        if (parts.length == 0) {
            // 구분자로만 된 이름("_", "-")은 나눈 결과가 비어 있다. 빈 이름처럼 다뤄 검사가 거부하게 둔다
            return "";
        }
        if (parts.length == 1) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            sb.append(capitalize(parts[i].toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    private static String joinPascal(String name) {
        if (name.isBlank()) {
            return "";
        }
        String[] parts = SEPARATOR.split(name);
        if (parts.length == 1) {
            return capitalize(name);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(capitalize(part.toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    private static boolean hasNonIdentifierChar(String name) {
        return name.codePoints().anyMatch(cp -> !Character.isJavaIdentifierPart(cp));
    }

    /** 식별자에 못 쓰는 글자를 _로 바꾼다. 이어지는 _는 구분자 하나로 읽힌다 */
    private static String separateNonIdentifierChars(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (Character.isJavaIdentifierPart(cp) || Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_');
            }
        });
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
