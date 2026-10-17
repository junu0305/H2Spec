package kr.go.h2spec.generator.emit;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IR의 이름이 생성 코드에서 컴파일되는지 보는 규칙.
 * CodeGenerator가 파일을 쓰기 전에 검사하고, parser-llm의 검증(V4·V6·V12)도 같은 규칙을 쓴다.
 *
 * <p>원래 이름이 식별자 꼴인지가 아니라, 원래 이름을 {@link JavaNames#camel}로 바꾼 Java 이름을 본다.
 * 원래 이름으로 거르면 잘 되는 이름(공백·한글·'-'가 든 이름. Java 이름으로 바뀌고 어노테이션이 원래 이름을 지킨다)을
 * 거부하고, 실제로 깨지는 이름(item.name, 예약어 class, station_name과 stationName)은 놓친다.
 */
public final class JavaNameRules {

    /** parser의 toApiId가 만드는 꼴. apiId는 클래스 이름이자 파일 이름이 되므로 이 꼴만 받는다 */
    private static final Pattern API_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private JavaNameRules() {
    }

    /**
     * 원래 이름(요청 파라미터 이름, 응답 경로의 칸)에 따옴표·역슬래시·제어문자가 없는지 본다.
     * 원래 이름은 어노테이션과 URL의 문자열 리터럴에 들어가므로 리터럴을 깰 수 있는 글자만 거른다.
     */
    public static boolean isSafeRawName(String rawName) {
        return rawName != null
                && rawName.chars().noneMatch(c -> c == '"' || c == '\\' || Character.isISOControl(c));
    }

    /** 원래 이름이 생성 코드에서 쓰일 Java 이름(필드·인자 이름). 생성기와 같은 변환이다 */
    public static String javaName(String rawName) {
        return JavaNames.camel(rawName);
    }

    /**
     * 이름을 생성 코드에 식별자로 넣어 컴파일되는지 본다.
     * {@link SourceVersion#isName}은 점으로 이은 이름(item.name)도 받으므로 쓰지 않는다.
     * javac는 식별자 안의 무시 가능 문자를 빼고 읽으므로 뺀 이름으로도 예약어인지 본다({@link #canonicalIdentifier}).
     */
    public static boolean isValidIdentifier(String name) {
        return name != null
                && SourceVersion.isIdentifier(name)
                && !SourceVersion.isKeyword(name)
                && !SourceVersion.isKeyword(canonicalIdentifier(name));
    }

    /** 원래 이름의 Java 이름이 식별자로 컴파일되는지 본다 */
    public static boolean hasValidJavaName(String rawName) {
        return rawName != null && isValidIdentifier(javaName(rawName));
    }

    /**
     * Java 이름이 같아지는 원래 이름을 묶는다. 한 메서드의 인자나 한 클래스의 필드끼리 겹치면 컴파일되지 않는다.
     * 키는 javac가 보는 Java 이름({@link #canonicalIdentifier}), 값은 입력 순서대로의 원래 이름이다.
     * 같은 이름이 두 번 와도 겹침이다. 겹치는 묶음만 담으므로 비어 있으면 겹침이 없다.
     * parser-llm(V12)은 묶음마다 앞의 것을 남긴다.
     */
    public static Map<String, List<String>> javaNameCollisions(Collection<String> rawNames) {
        Map<String, List<String>> byJavaName = new LinkedHashMap<>();
        for (String rawName : rawNames) {
            byJavaName.computeIfAbsent(canonicalIdentifier(javaName(rawName)), key -> new ArrayList<>())
                    .add(rawName);
        }
        byJavaName.values().removeIf(names -> names.size() < 2);
        return byJavaName;
    }

    /**
     * DTO 필드의 getter·setter 이름이 같아지는 원래 이름을 묶는다. getter·setter 이름은 필드 이름({@link #javaName})이 아니라
     * {@link JavaNames#pascal}로 만들어서, 필드 이름은 첫 글자 대소문자만 달라 겹치지 않아도({@code items}와 {@code _items}의
     * {@code Items}) getter·setter는 같아져({@code getItems}) 컴파일되지 않는다. 필드 이름이 같아지는지는 따로
     * {@link #javaNameCollisions}로 본다. 둘은 서로를 다 덮지 않는다({@code İtems}와 {@code items}는 필드 이름만 같다).
     * 키는 {@link #accessorKey}, 값은 입력 순서대로의 원래 이름이다. 겹치는 묶음만 담는다.
     */
    public static Map<String, List<String>> accessorNameCollisions(Collection<String> rawNames) {
        Map<String, List<String>> byAccessor = new LinkedHashMap<>();
        for (String rawName : rawNames) {
            byAccessor.computeIfAbsent(accessorKey(rawName), key -> new ArrayList<>()).add(rawName);
        }
        byAccessor.values().removeIf(names -> names.size() < 2);
        return byAccessor;
    }

    /**
     * @param rawName 응답 경로의 칸 이름
     * @return javac가 보는 getter·setter 이름에서 get·set을 뺀 부분({@code getItems}의 {@code Items})
     */
    public static String accessorKey(String rawName) {
        return canonicalIdentifier(JavaNames.pascal(rawName));
    }

    /**
     * javac가 두 식별자를 같은지 볼 때의 꼴. javac는 무시 가능 문자(soft hyphen·zero width space 같은 서식 문자)를
     * 빼고 읽어서, 문서에서 따라 들어온 이런 글자 하나 차이인 두 이름은 같은 이름이 된다.
     */
    public static String canonicalIdentifier(String identifier) {
        StringBuilder sb = new StringBuilder(identifier.length());
        identifier.codePoints()
                .filter(cp -> !Character.isIdentifierIgnorable(cp))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /**
     * 패키지 이름을 본다. 점으로 이은 식별자이고 마디가 예약어가 아니어야 한다({@link SourceVersion#isName}).
     * CLI의 --package가 덧붙이는 apiId 소문자 마디도 int·new 같은 예약어가 될 수 있다.
     */
    public static boolean isValidPackageName(String packageName) {
        if (packageName == null || !SourceVersion.isName(packageName)) {
            return false;
        }
        for (String segment : packageName.split("\\.")) {
            if (!isValidIdentifier(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * apiId가 toApiId가 만드는 꼴(영문자로 시작하고 영문자·숫자·_만)인지 본다.
     * apiId는 클래스 이름과 파일 경로가 되므로 "../" 같은 경로가 들어가면 출력 디렉터리 밖에 써진다.
     */
    public static boolean isValidApiId(String apiId) {
        return apiId != null && API_ID.matcher(apiId).matches();
    }
}
