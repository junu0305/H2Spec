package kr.go.h2spec.generator;

import kr.go.h2spec.generator.emit.ClientEmitter;
import kr.go.h2spec.generator.emit.DtoEmitter;
import kr.go.h2spec.generator.emit.JavaNameRules;
import kr.go.h2spec.generator.emit.JavaNames;
import kr.go.h2spec.generator.emit.JavaText;
import kr.go.h2spec.generator.emit.OpenApiEmitter;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.ir.ResponseField;
import kr.go.h2spec.generator.model.DtoNode;
import kr.go.h2spec.generator.model.DtoTreeBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** IR JSON → DTO/클라이언트 소스 파일 생성 퍼사드. */
public class CodeGenerator {

    /** 포맷 파라미터 값으로 받는 글자. 값은 인코딩 없이 URL에 들어간다 */
    private static final Pattern FORMAT_VALUE = Pattern.compile("[A-Za-z0-9._-]+");

    public List<Path> generate(Path irJson, Path outputDir) throws IOException {
        return generate(new IrLoader().load(irJson), outputDir);
    }

    /**
     * 생성 코드가 컴파일되지 않거나 출력 디렉터리 밖에 써질 IR은 파일을 쓰기 전에 IllegalArgumentException으로 거부한다.
     * 검사를 IrLoader가 아니라 여기서 하는 것은 CLI가 IrLoader 뒤에 --package 같은 덮어쓰기 옵션을 적용하고,
     * 이 메서드는 IrLoader를 거치지 않고도 불리기 때문이다.
     */
    public List<Path> generate(IrSpec ir, Path outputDir) throws IOException {
        validateNames(ir);
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());
        validateFieldNames(root, root.name());

        // 셋을 모두 메모리에 만든 뒤에 쓴다. DTO를 먼저 써 두면 ClientEmitter가 실패할 때(serviceKey 누락 등) DTO만 남는다.
        // UTF-8로 쓸 수 없는 글(짝 없는 서로게이트)도 쓰기 전에 드러나도록 바이트까지 만들어 둔다.
        byte[] dtoSource = utf8(new DtoEmitter().emit(ir, root), "DTO");
        byte[] clientSource = utf8(new ClientEmitter().emit(ir), "클라이언트");
        byte[] openApiSource = utf8(new OpenApiEmitter().emit(ir, root), "OpenAPI 스펙");

        String pkgPath = ir.generatorHints().targetPackage().replace('.', '/');
        String apiClass = JavaNames.pascal(ir.api().apiId());

        Path dtoFile = outputDir.resolve(pkgPath).resolve("dto").resolve(apiClass + "Response.java");
        Path clientFile = outputDir.resolve(pkgPath).resolve(apiClass + "Client.java");
        Path openApiFile = outputDir.resolve("openapi").resolve(ir.api().apiId() + ".json");

        Files.createDirectories(dtoFile.getParent());
        Files.createDirectories(openApiFile.getParent());
        Files.write(dtoFile, dtoSource);
        Files.write(clientFile, clientSource);
        Files.write(openApiFile, openApiSource);
        return List.of(dtoFile, clientFile, openApiFile);
    }

    // 컴파일되지 않을 코드와 출력 디렉터리 밖의 경로만 거부한다. 공백·한글·'-'가 든 이름(relevant departments, 측정소,
    // sido-name)은 JavaNames가 Java 이름으로 바꾸고 어노테이션이 원래 이름을 지켜 잘 되므로 막지 않는다.
    private void validateNames(IrSpec ir) {
        // apiId와 패키지는 클래스 이름이자 파일 경로가 된다
        String apiId = ir.api().apiId();
        if (!JavaNameRules.isValidApiId(apiId)) {
            throw new IllegalArgumentException("api.apiId 형식이 잘못되었습니다: " + quote(apiId)
                    + " — 영문자로 시작하고 영문자·숫자·_만 쓸 수 있습니다.");
        }
        String targetPackage = ir.generatorHints().targetPackage();
        if (!JavaNameRules.isValidPackageName(targetPackage)) {
            throw new IllegalArgumentException("generatorHints.targetPackage 형식이 잘못되었습니다: " + quote(targetPackage)
                    + " — 점으로 이은 Java 식별자여야 하고 마디에 예약어를 쓸 수 없습니다.");
        }
        // 포맷 파라미터 값은 인코딩 없이 URL에 들어간다
        GeneratorHints.ResponseFormatParameter formatHint = ir.generatorHints().responseFormatParameter();
        if (formatHint != null && formatHint.value() != null && !formatHint.value().isBlank()
                && !FORMAT_VALUE.matcher(formatHint.value()).matches()) {
            throw new IllegalArgumentException("generatorHints.responseFormatParameter.value 형식이 잘못되었습니다: "
                    + quote(formatHint.value()) + " — 영문자·숫자·.·_·-만 쓸 수 있습니다.");
        }
        // 힌트가 가리킨 파라미터는 메서드 인자에서 빠지고 쿼리에 값이 붙는다. 경로 변수면 주소의 {이름}이 남고,
        // 인증키(serviceKey)면 키 자리에 형식 값이 들어간다
        if (formatHint != null) {
            for (RequestParameter p : ir.api().requestParameters()) {
                if (p.name().equals(formatHint.name())
                        && (!"query".equals(p.in()) || "serviceKey".equalsIgnoreCase(p.name()))) {
                    throw new IllegalArgumentException("generatorHints.responseFormatParameter가 가리킬 수 없는 파라미터입니다: "
                            + quote(p.name()) + " — serviceKey가 아닌 쿼리 파라미터여야 합니다.");
                }
            }
        }
        // 원래 이름은 어노테이션과 URL의 문자열 리터럴에 그대로 들어간다
        for (RequestParameter p : ir.api().requestParameters()) {
            if (!JavaNameRules.isSafeRawName(p.name())) {
                throw new IllegalArgumentException("요청 파라미터 이름에 쓸 수 없는 글자가 있습니다: " + quote(p.name())
                        + " — 따옴표·역슬래시·제어문자는 쓸 수 없습니다.");
            }
        }
        for (ResponseField f : ir.api().responseFields()) {
            if (!JavaNameRules.isSafeRawName(f.path())) {
                throw new IllegalArgumentException("응답 필드 경로에 쓸 수 없는 글자가 있습니다: " + quote(f.path())
                        + " — 따옴표·역슬래시·제어문자는 쓸 수 없습니다.");
            }
        }
        // serviceKey·포맷 파라미터는 문자열로만 들어가고, 메서드 인자가 되는 파라미터만 Java 이름이 변수가 된다
        List<String> argNames = ClientEmitter.methodParameters(ir).stream().map(RequestParameter::name).toList();
        for (String name : argNames) {
            if (!JavaNameRules.hasValidJavaName(name)) {
                throw new IllegalArgumentException("요청 파라미터 이름을 Java 이름으로 쓸 수 없습니다: " + quote(name)
                        + " → " + quote(JavaNameRules.javaName(name)) + " — 식별자가 아니거나 예약어입니다.");
            }
        }
        Map<String, List<String>> collisions = JavaNameRules.javaNameCollisions(argNames);
        if (!collisions.isEmpty()) {
            Map.Entry<String, List<String>> first = collisions.entrySet().iterator().next();
            throw new IllegalArgumentException("요청 파라미터의 Java 이름 충돌: " + quote(first.getKey())
                    + " (" + String.join(", ", first.getValue()) + ") — 한 메서드의 인자가 되므로 이름을 구분해 주세요.");
        }
    }

    // 노드마다 클래스 하나가 되고(루트는 루트 클래스, 내부 노드는 중첩 클래스) 자식 노드가 그 클래스의 필드다.
    // 루트 이름은 어노테이션 문자열에만 들어가므로 Java 이름을 보지 않는다.
    private void validateFieldNames(DtoNode node, String path) {
        for (DtoNode child : node.children().values()) {
            if (!JavaNameRules.hasValidJavaName(child.name())) {
                throw new IllegalArgumentException("응답 필드 이름을 Java 이름으로 쓸 수 없습니다: " + quote(child.name())
                        + " (" + path + "." + child.name() + ") → " + quote(JavaNameRules.javaName(child.name()))
                        + " — 식별자가 아니거나 예약어입니다.");
            }
            // getter가 Object의 final 메서드 getClass()가 되는 이름("_class" → Class)은 컴파일되지 않는다
            if (JavaNameRules.accessorKey(child.name()).equals("Class")) {
                throw new IllegalArgumentException("응답 필드 이름의 getter가 Object.getClass()와 겹칩니다: " + quote(child.name())
                        + " (" + path + "." + child.name() + ") — IR 경로 세그먼트 이름을 바꿔 주세요.");
            }
        }
        Map<String, List<String>> collisions = JavaNameRules.javaNameCollisions(node.children().keySet());
        if (!collisions.isEmpty()) {
            Map.Entry<String, List<String>> first = collisions.entrySet().iterator().next();
            List<String> paths = first.getValue().stream().map(name -> path + "." + name).toList();
            throw new IllegalArgumentException("응답 필드의 Java 이름 충돌: " + quote(first.getKey())
                    + " (" + String.join(", ", paths) + ") — 한 클래스의 필드가 되므로 IR 경로 세그먼트 이름을 구분해 주세요.");
        }
        // 필드 이름은 달라도 첫 글자 대소문자만 다르면 getter·setter가 같아진다(items와 _items → getItems)
        Map<String, List<String>> accessors = JavaNameRules.accessorNameCollisions(node.children().keySet());
        if (!accessors.isEmpty()) {
            Map.Entry<String, List<String>> first = accessors.entrySet().iterator().next();
            List<String> paths = first.getValue().stream().map(name -> path + "." + name).toList();
            throw new IllegalArgumentException("응답 필드의 getter 이름 충돌: " + quote("get" + first.getKey())
                    + " (" + String.join(", ", paths) + ") — 한 클래스의 getter·setter가 되므로 IR 경로 세그먼트 이름을 구분해 주세요.");
        }
        for (DtoNode child : node.children().values()) {
            if (!child.isLeaf()) {
                validateFieldNames(child, path + "." + child.name());
            }
        }
    }

    /** 메시지에 IR 값을 보일 때 제어문자를 escape해서 보인다. 터미널 제어 문자가 그대로 출력되지 않게 한다 */
    private static String quote(String value) {
        return "'" + JavaText.literal(value) + "'";
    }

    /** @param what 메시지에 쓸 산출물 이름 */
    private static byte[] utf8(String source, String what) {
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(source));
        } catch (CharacterCodingException e) {
            // 인코더의 메시지("Input length = 1")로는 원인을 알 수 없어 무엇이 문제인지 적는다
            throw new IllegalArgumentException(what + " 코드에 UTF-8로 쓸 수 없는 글자(짝 없는 서로게이트)가 있습니다"
                    + " — IR의 이름·설명 문자열을 확인해 주세요.", e);
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }
}
