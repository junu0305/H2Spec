package kr.go.h2spec.generator.emit;

import java.util.regex.Pattern;

/** IR의 파라미터/경로 이름을 Java 식별자 규칙으로 변환한다. */
public final class JavaNames {

    private static final Pattern SEPARATOR = Pattern.compile("[_\\-\\s]+");

    private JavaNames() {
    }

    /** 구분자(_,-,공백)가 있으면 camelCase로 합치고, 없으면 첫 글자만 소문자로 만든다. */
    public static String camel(String name) {
        String[] parts = SEPARATOR.split(name);
        if (parts.length == 1) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            sb.append(capitalize(parts[i].toLowerCase()));
        }
        return sb.toString();
    }

    /** 구분자가 있으면 PascalCase로 합치고, 없으면 첫 글자만 대문자로 만든다. */
    public static String pascal(String name) {
        String[] parts = SEPARATOR.split(name);
        if (parts.length == 1) {
            return capitalize(name);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(capitalize(part.toLowerCase()));
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
