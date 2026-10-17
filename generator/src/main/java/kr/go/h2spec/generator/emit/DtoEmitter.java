package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.model.DtoNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DTO 트리를 Jackson 어노테이션(응답 포맷에 따라 XML용 또는 JSON용)이 붙은 단일 .java 소스로 방출한다. */
public class DtoEmitter {

    private static final String INDENT = "    ";
    private static final Map<String, String> TYPE_MAP = Map.of(
            "string", "String",
            "integer", "Long",
            "number", "Double",
            "boolean", "Boolean");
    private static final String DEFAULT_TYPE = "String";
    private static final String XML_FORMAT = "XML";
    /**
     * 생성 DTO가 이름으로 부르는 타입(필드 타입, import하는 타입, 역직렬화기가 쓰는 java.lang 타입). 중첩 클래스 이름은 루트 클래스 본문 전체에서
     * 같은 이름의 import를 가리므로, 중첩 클래스가 이 이름이면 List 필드나 어노테이션이 그 클래스를 가리켜 컴파일되지 않고
     * String 같은 필드는 엉뚱한 타입이 된다. 포맷마다 import가 다르지만 같은 IR이 포맷과 상관없이 같은 클래스 이름을 갖도록
     * 두 포맷의 이름을 모두 피한다. TYPE_MAP이나 appendImports에 타입을 더하면 여기에도 더한다.
     */
    private static final Set<String> RESERVED_TYPE_NAMES = Set.of(
            "List", "String", "Long", "Double", "Boolean",
            "JsonIgnoreProperties", "JsonProperty", "JsonCreator", "JsonRootName",
            "JacksonXmlRootElement", "JacksonXmlProperty", "JacksonXmlElementWrapper",
            "IOException", "JsonParser", "DeserializationContext", "JsonDeserializer", "JsonDeserialize",
            "Override", "NumberFormatException", "LenientLong", "LenientDouble", "LenientBoolean");
    /**
     * 숫자·불리언 필드를 너그럽게 읽는 역직렬화기의 클래스 이름. 공공데이터 응답은 값이 없는 측정값에 "-"를 넣거나 금액에 천 단위
     * 쉼표("82,500")를 넣어, 그대로 읽으면 값 하나 때문에 응답 전체를 읽지 못한다. 생성 DTO가 interceptor-core 같은 다른 라이브러리의
     * 판에 기대지 않도록 쓰는 것만 루트 클래스 안에 만든다(appendLenientDeserializers)
     */
    private static final Map<String, String> LENIENT_DESERIALIZERS = Map.of(
            "Long", "LenientLong", "Double", "LenientDouble", "Boolean", "LenientBoolean");
    /** 루트 클래스 안에 만드는 차례 */
    private static final List<String> LENIENT_TYPES = List.of("Long", "Double", "Boolean");

    public String emit(IrSpec ir, DtoNode root) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Response";
        boolean isXml = XML_FORMAT.equals(ir.api().responseFormat());
        // 루트 노드에 이름이 없으면 루트 없는 응답이다(DtoTreeBuilder). XML 문서는 루트 요소가 늘 하나라 그럴 수 없다
        boolean rootless = root.name() == null;
        if (rootless && isXml) {
            throw new IllegalArgumentException("XML 응답은 루트 요소가 하나라 루트 없는 응답으로 만들 수 없습니다.");
        }
        Map<DtoNode, String> classNames = assignClassNames(root, className);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(".dto;\n\n");
        appendImports(sb, root, isXml);
        sb.append("/**\n")
          .append(" * ").append(JavaText.comment(ir.api().apiName())).append(" 응답 DTO.\n")
          .append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n")
          .append(" */\n");
        sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        if (isXml) {
            sb.append("@JacksonXmlRootElement(localName = \"").append(JavaText.literal(root.name())).append("\")\n");
        } else if (!rootless) {
            // JSON은 루트가 {"response":{...}}처럼 키로 들어온다. 클라이언트의
            // UNWRAP_ROOT_VALUE와 짝을 이뤄야 하며, 없으면 전 필드가 null인 객체가 조용히 나온다.
            // 루트 없는 응답은 이 클래스가 응답 전체라 클라이언트도 루트를 풀지 않는다
            sb.append("@JsonRootName(\"").append(JavaText.literal(root.name())).append("\")\n");
        }
        sb.append("public class ").append(className).append(" {\n");
        appendClassBody(sb, root, 1, isXml, classNames);
        appendNestedClasses(sb, root, 1, isXml, classNames);
        appendLenientDeserializers(sb, lenientTypes(root));
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 내부 노드마다 중첩 클래스 이름을 정한다. 내부 노드는 모두 루트 클래스 바로 아래 형제 중첩 클래스가 되므로
     * (appendNestedClass) 트리 전체에서 이름이 겹치면 "already defined"로 컴파일되지 않는다.
     * 파일에 나오는 차례(전위 순회)로 정해서 먼저 나오는 클래스가 원래 이름을 갖고, 같은 IR에서는 늘 같은 이름이 나온다.
     * <ol>
     *   <li>pascal(이름). 앞서 정한 클래스, 루트 클래스, 예약된 타입 이름과 겹치지 않으면 그대로 쓴다. 겹침이 없는 IR은 이름이 바뀌지 않는다</li>
     *   <li>부모 클래스 이름 + pascal(이름). 응답 경로를 따라 읽히는 이름이 된다(result.list → ResultList, items.item → ItemsItem).
     *       루트의 자식은 루트 클래스 대신 루트 요소 이름을 붙인다(루트 없는 응답은 루트 클래스 이름).
     *       부모 이름도 겹치지 않게 정했으므로 대개 여기서 끝난다</li>
     *   <li>그래도 겹치면 pascal(이름) 뒤에 2부터 차례로 번호를 붙인다(List2)</li>
     * </ol>
     * 필드 이름과 어노테이션은 원래 이름을 그대로 쓰므로 클래스 이름이 바뀌어도 JSON·XML 매핑은 같다.
     */
    private Map<DtoNode, String> assignClassNames(DtoNode root, String rootClassName) {
        Map<DtoNode, String> classNames = new IdentityHashMap<>();
        String rootPrefix = root.name() == null ? rootClassName : JavaNames.pascal(root.name());
        assignNestedClassNames(root, rootPrefix, rootClassName, classNames, new HashSet<>());
        return classNames;
    }

    private void assignNestedClassNames(DtoNode node, String nodeClassName, String rootClassName,
                                        Map<DtoNode, String> classNames, Set<String> takenKeys) {
        for (DtoNode child : node.children().values()) {
            if (child.isLeaf()) {
                continue;
            }
            String className = nestedClassName(child, nodeClassName, rootClassName, takenKeys);
            classNames.put(child, className);
            takenKeys.add(classFileKey(className));
            assignNestedClassNames(child, className, rootClassName, classNames, takenKeys);
        }
    }

    private String nestedClassName(DtoNode node, String parentClassName, String rootClassName, Set<String> takenKeys) {
        String base = JavaNames.pascal(node.name());
        if (isAvailable(base, rootClassName, takenKeys)) {
            return base;
        }
        // 루트 요소 이름은 Java 이름으로 검사하지 않으므로(어노테이션에만 들어간다) 붙인 이름이 식별자인지 본다
        String prefixed = parentClassName + base;
        if (JavaNameRules.isValidIdentifier(prefixed) && isAvailable(prefixed, rootClassName, takenKeys)) {
            return prefixed;
        }
        for (int n = 2; ; n++) {
            String numbered = base + n;
            if (isAvailable(numbered, rootClassName, takenKeys)) {
                return numbered;
            }
        }
    }

    // 예약된 타입 이름과 루트 클래스는 javac가 보는 이름이 같을 때만 겹친다.
    // 대소문자가 다르면 다른 타입이고, 중첩 클래스의 class 파일 이름(Outer$Name.class)과도 겹치지 않는다.
    private boolean isAvailable(String className, String rootClassName, Set<String> takenKeys) {
        String javacName = JavaNameRules.canonicalIdentifier(className);
        return !RESERVED_TYPE_NAMES.contains(javacName)
                && !javacName.equals(rootClassName)
                && !takenKeys.contains(classFileKey(className));
    }

    /**
     * 중첩 클래스끼리 겹치는지 볼 때의 키. javac가 보는 이름(무시 가능 문자를 뺀 이름)을 소문자로 바꾼다.
     * 대소문자만 다른 두 중첩 클래스(Items, ITEMS)는 컴파일은 되지만, class 파일(Outer$Items.class, Outer$ITEMS.class)이
     * 대소문자를 가리지 않는 파일 시스템(Windows, macOS 기본)에서 한 파일이 되어 하나가 다른 하나를 덮어쓴다.
     */
    private static String classFileKey(String className) {
        return JavaNameRules.canonicalIdentifier(className).toLowerCase(Locale.ROOT);
    }

    // 트리에 리스트가 있으면 java.util.List, 숫자·불리언 필드가 있으면 java.io.IOException + 빈 줄. 항상 JsonIgnoreProperties.
    // XML은 리스트 있으면 ElementWrapper/Property, 원본명!=camel명인 필드가 있으면 Property, 항상 RootElement. JSON은 리스트나
    // 원본명!=camel명인 필드가 있으면 JsonProperty, 리스트 래퍼가 있으면 JsonCreator, 루트가 있으면 JsonRootName.
    // 숫자·불리언 필드가 있으면 역직렬화기에 쓰는 네 타입. 마지막에 빈 줄.
    private void appendImports(StringBuilder sb, DtoNode root, boolean isXml) {
        boolean hasList = hasList(root);
        boolean hasRenamedField = hasRenamedField(root);
        boolean lenient = !lenientTypes(root).isEmpty();
        if (lenient) {
            sb.append("import java.io.IOException;\n");
        }
        if (hasList) {
            sb.append("import java.util.List;\n");
        }
        if (lenient || hasList) {
            sb.append("\n");
        }
        sb.append("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;\n");
        if (!isXml) {
            if (hasList || hasRenamedField) {
                sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
            }
            if (hasListWrapper(root)) {
                sb.append("import com.fasterxml.jackson.annotation.JsonCreator;\n");
            }
            if (root.name() != null) {
                sb.append("import com.fasterxml.jackson.annotation.JsonRootName;\n");
            }
        } else {
            if (hasList) {
                sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;\n");
            }
            if (hasList || hasRenamedField) {
                sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;\n");
            }
            sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;\n");
        }
        if (lenient) {
            sb.append("import com.fasterxml.jackson.core.JsonParser;\n");
            sb.append("import com.fasterxml.jackson.databind.DeserializationContext;\n");
            sb.append("import com.fasterxml.jackson.databind.JsonDeserializer;\n");
            sb.append("import com.fasterxml.jackson.databind.annotation.JsonDeserialize;\n");
        }
        sb.append("\n");
    }

    /** 리프의 역직렬화기 클래스 이름. 너그럽게 읽을 타입(숫자·불리언)이 아니면 null */
    private static String lenientDeserializer(DtoNode node) {
        return node.isLeaf() ? LENIENT_DESERIALIZERS.get(TYPE_MAP.getOrDefault(node.leafType(), DEFAULT_TYPE)) : null;
    }

    /** 트리의 숫자·불리언 리프 타입. 루트 클래스 안에 만드는 차례(Long, Double, Boolean)다 */
    private static List<String> lenientTypes(DtoNode root) {
        Set<String> used = new HashSet<>();
        collectLeafTypes(root, used);
        return LENIENT_TYPES.stream().filter(used::contains).toList();
    }

    private static void collectLeafTypes(DtoNode node, Set<String> used) {
        for (DtoNode child : node.children().values()) {
            if (child.isLeaf()) {
                used.add(TYPE_MAP.getOrDefault(child.leafType(), DEFAULT_TYPE));
            } else {
                collectLeafTypes(child, used);
            }
        }
    }

    /** 리스트 필드 하나만 가진 래퍼 노드가 있는지 (배열 수용 팩토리 방출 대상) */
    private boolean hasListWrapper(DtoNode node) {
        for (DtoNode child : node.children().values()) {
            List<DtoNode> grandChildren = new ArrayList<>(child.children().values());
            if (grandChildren.size() == 1 && grandChildren.get(0).isList()) {
                return true;
            }
            if (hasListWrapper(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasList(DtoNode node) {
        for (DtoNode child : node.children().values()) {
            if (child.isList() || hasList(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRenamedField(DtoNode node) {
        for (DtoNode child : node.children().values()) {
            if (needsExplicitName(child) || hasRenamedField(child)) {
                return true;
            }
        }
        return false;
    }

    // 필드 선언 전부(javadoc, 리스트 어노테이션, 이름 어노테이션 포함) → getter/setter 전부.
    // javadoc·어노테이션이 붙은 필드 선언의 앞뒤와 getter/setter 사이마다 빈 줄.
    private void appendClassBody(StringBuilder sb, DtoNode node, int depth, boolean isXml,
                                 Map<DtoNode, String> classNames) {
        String indent = INDENT.repeat(depth);
        List<DtoNode> fields = new ArrayList<>(node.children().values());

        sb.append("\n");
        boolean prevDecorated = false;
        for (int i = 0; i < fields.size(); i++) {
            DtoNode field = fields.get(i);
            boolean decorated = isDecorated(field);
            if (i > 0 && (decorated || prevDecorated)) {
                sb.append("\n");
            }
            appendFieldDeclaration(sb, field, indent, isXml, classNames);
            prevDecorated = decorated;
        }

        sb.append("\n");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            appendGetter(sb, fields.get(i), indent, depth, isXml, classNames);
            sb.append("\n");
            appendSetter(sb, fields.get(i), indent, depth, classNames);
        }
    }

    private boolean isDecorated(DtoNode node) {
        if (node.isLeaf() && node.description() != null) {
            return true;
        }
        if (node.isList() || lenientDeserializer(node) != null) {
            return true;
        }
        return needsExplicitName(node);
    }

    /**
     * 필드에 원래 이름을 적어야 하는지. Java 이름이 원래 이름과 다르거나, getter에서 Jackson이 읽는 이름이 원래 이름과 다를 때다
     * ({@link #needsGetterName}). 적지 않으면 Jackson이 다른 이름으로 찾아 그 값을 조용히 버린다
     */
    private static boolean needsExplicitName(DtoNode node) {
        return !node.name().equals(JavaNames.camel(node.name())) || needsGetterName(node);
    }

    /**
     * getter에도 원래 이름을 적어야 하는지. Jackson은 getter 이름에서 get을 떼고 맨 앞의 대문자들을 소문자로 바꿔 속성 이름을 짓는다
     * ({@code getSGubun} → {@code sgubun}, {@code getXPos} → {@code xpos}). 그 이름이 필드 이름({@code sGubun}, {@code xPos})과 다르면
     * Jackson은 둘을 다른 속성으로 본다. 필드에만 원래 이름을 적으면 읽기는 되지만 직렬화할 때 getter의 이름으로 한 번 더 나가므로
     * getter에도 적어 한 속성으로 묶는다. 두 번째 글자가 대문자인 이름(sGubun, eUserNo, xPos)과 그렇게 바뀌는 이름(X_POS)이 여기에 든다
     */
    private static boolean needsGetterName(DtoNode node) {
        return !jacksonPropertyName(JavaNames.pascal(node.name())).equals(JavaNames.camel(node.name()));
    }

    /** getter 이름에서 get을 뗀 부분으로 Jackson(기본 설정)이 짓는 속성 이름. 맨 앞부터 대문자인 글자를 소문자로 바꾼다 */
    private static String jacksonPropertyName(String accessorSuffix) {
        StringBuilder sb = new StringBuilder(accessorSuffix.length());
        int i = 0;
        for (; i < accessorSuffix.length(); i++) {
            char c = accessorSuffix.charAt(i);
            char lower = Character.toLowerCase(c);
            if (c == lower) {
                break;
            }
            sb.append(lower);
        }
        return sb.append(accessorSuffix, i, accessorSuffix.length()).toString();
    }

    private void appendFieldDeclaration(StringBuilder sb, DtoNode node, String indent, boolean isXml,
                                        Map<DtoNode, String> classNames) {
        if (node.isLeaf() && node.description() != null) {
            sb.append(indent).append("/** ").append(JavaText.comment(node.description())).append(" */\n");
        }
        boolean renamed = needsExplicitName(node);
        String nameLiteral = JavaText.literal(node.name());
        if (!isXml) {
            // JSON 배열에는 XML의 래퍼 요소 개념이 없어 이름만 맞춰주면 된다
            if (node.isList() || renamed) {
                sb.append(indent).append("@JsonProperty(\"").append(nameLiteral).append("\")\n");
            }
        } else if (node.isList()) {
            sb.append(indent).append("@JacksonXmlElementWrapper(useWrapping = false)\n");
            sb.append(indent).append("@JacksonXmlProperty(localName = \"").append(nameLiteral).append("\")\n");
        } else if (renamed) {
            sb.append(indent).append("@JacksonXmlProperty(localName = \"").append(nameLiteral).append("\")\n");
        }
        String lenient = lenientDeserializer(node);
        if (lenient != null) {
            // 리스트면 요소마다 쓴다
            sb.append(indent).append(node.isList() ? "@JsonDeserialize(contentUsing = " : "@JsonDeserialize(using = ")
              .append(lenient).append(".class)\n");
        }
        sb.append(indent).append("private ").append(fieldType(node, classNames)).append(" ")
          .append(JavaNames.camel(node.name())).append(";\n");
    }

    /**
     * 숫자·불리언 필드의 역직렬화기를 루트 클래스 안에 만든다. JSON의 숫자·불리언 값은 그대로 읽는다. 글로 온 값(XML은 모든 값이 글이다)은
     * 앞뒤 공백을 떼고 읽는데, 숫자는 천 단위로 끊은 쉼표("82,500")만 떼고, 읽을 수 없는 글("-", "")은 응답 전체를 실패시키지 않고 null로 둔다.
     * 소수점 쉼표("1,5")처럼 천 단위 꼴이 아닌 쉼표는 떼지 않아 null이 된다(다른 값으로 잘못 읽지 않는다). 불리언은 Y·N과 1·0도 읽는다.
     * 객체·배열처럼 값이 아닌 것은 지금처럼 실패한다. 역직렬화기 안의 타입은 import한 이름만 쓴다. 완전한 이름(java.util.…)은
     * 이름이 java나 com인 응답 필드에 가려진다.
     */
    private void appendLenientDeserializers(StringBuilder sb, List<String> types) {
        for (String type : types) {
            sb.append("\n");
            switch (type) {
                case "Long" -> appendNumberDeserializer(sb, "LenientLong", "Long", "getLongValue",
                        "숫자 칸을 너그럽게 읽는다. 천 단위 쉼표(\"82,500\")와 앞뒤 공백은 떼고, 값이 없을 때의 \"-\"처럼 숫자가 아닌 글은 null로 둔다");
                case "Double" -> appendNumberDeserializer(sb, "LenientDouble", "Double", "getDoubleValue",
                        "실수 칸을 너그럽게 읽는다. 천 단위 쉼표와 앞뒤 공백은 떼고, 값이 없을 때의 \"-\"처럼 숫자가 아닌 글은 null로 둔다");
                default -> appendBooleanDeserializer(sb);
            }
        }
    }

    private void appendNumberDeserializer(StringBuilder sb, String className, String type, String numberGetter,
                                          String javadoc) {
        String i1 = INDENT;
        String i2 = INDENT.repeat(2);
        String i3 = INDENT.repeat(3);
        String i4 = INDENT.repeat(4);
        sb.append(i1).append("/** ").append(javadoc).append(" */\n");
        sb.append(i1).append("public static class ").append(className).append(" extends JsonDeserializer<").append(type)
          .append("> {\n");
        sb.append(i2).append("@Override\n");
        sb.append(i2).append("public ").append(type)
          .append(" deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {\n");
        sb.append(i3).append("if (p.currentToken().isNumeric()) {\n");
        sb.append(i4).append("return p.").append(numberGetter).append("();\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("String text = p.getValueAsString();\n");
        sb.append(i3).append("if (text == null) {\n");
        sb.append(i4).append("return (").append(type).append(") ctxt.handleUnexpectedToken(").append(type)
          .append(".class, p);\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("String number = text.strip();\n");
        sb.append(i3).append("if (number.matches(\"[-+]?\\\\d{1,3}(,\\\\d{3})+(\\\\.\\\\d+)?\")) {\n");
        sb.append(i4).append("number = number.replace(\",\", \"\");\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("try {\n");
        sb.append(i4).append("return ").append(type).append(".valueOf(number);\n");
        sb.append(i3).append("} catch (NumberFormatException e) {\n");
        sb.append(i4).append("return null;\n");
        sb.append(i3).append("}\n");
        sb.append(i2).append("}\n");
        sb.append(i1).append("}\n");
    }

    private void appendBooleanDeserializer(StringBuilder sb) {
        String i1 = INDENT;
        String i2 = INDENT.repeat(2);
        String i3 = INDENT.repeat(3);
        String i4 = INDENT.repeat(4);
        sb.append(i1).append("/** 불리언 칸을 너그럽게 읽는다. true·false에 더해 Y·N과 1·0도 읽고, 그 밖의 글은 null로 둔다 */\n");
        sb.append(i1).append("public static class LenientBoolean extends JsonDeserializer<Boolean> {\n");
        sb.append(i2).append("@Override\n");
        sb.append(i2).append("public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {\n");
        sb.append(i3).append("if (p.currentToken().isBoolean()) {\n");
        sb.append(i4).append("return p.getBooleanValue();\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("if (p.currentToken().isNumeric()) {\n");
        sb.append(i4).append("return p.getLongValue() != 0;\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("String text = p.getValueAsString();\n");
        sb.append(i3).append("if (text == null) {\n");
        sb.append(i4).append("return (Boolean) ctxt.handleUnexpectedToken(Boolean.class, p);\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("String value = text.strip();\n");
        sb.append(i3).append("if (value.equalsIgnoreCase(\"true\") || value.equalsIgnoreCase(\"Y\") || value.equals(\"1\")) {\n");
        sb.append(i4).append("return true;\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("if (value.equalsIgnoreCase(\"false\") || value.equalsIgnoreCase(\"N\") || value.equals(\"0\")) {\n");
        sb.append(i4).append("return false;\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("return null;\n");
        sb.append(i2).append("}\n");
        sb.append(i1).append("}\n");
    }

    private void appendGetter(StringBuilder sb, DtoNode node, String indent, int depth, boolean isXml,
                              Map<DtoNode, String> classNames) {
        String type = fieldType(node, classNames);
        String fieldName = JavaNames.camel(node.name());
        String bodyIndent = INDENT.repeat(depth + 1);
        if (needsGetterName(node)) {
            String nameLiteral = JavaText.literal(node.name());
            sb.append(indent).append(isXml
                    ? "@JacksonXmlProperty(localName = \"" + nameLiteral + "\")\n"
                    : "@JsonProperty(\"" + nameLiteral + "\")\n");
        }
        sb.append(indent).append("public ").append(type).append(" get")
          .append(JavaNames.pascal(node.name())).append("() {\n");
        sb.append(bodyIndent).append("return ").append(fieldName).append(";\n");
        sb.append(indent).append("}\n");
    }

    private void appendSetter(StringBuilder sb, DtoNode node, String indent, int depth,
                              Map<DtoNode, String> classNames) {
        String type = fieldType(node, classNames);
        String fieldName = JavaNames.camel(node.name());
        String bodyIndent = INDENT.repeat(depth + 1);
        sb.append(indent).append("public void set").append(JavaNames.pascal(node.name()))
          .append("(").append(type).append(" ").append(fieldName).append(") {\n");
        sb.append(bodyIndent).append("this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        sb.append(indent).append("}\n");
    }

    // 내부 노드 DFS 순회(pre-order), 각 노드당 "@JsonIgnoreProperties..." + "public static class 클래스이름" 방출.
    // 중첩이 아니라 루트 바로 아래 형제로 flat하게 나열한다. 클래스 이름은 assignClassNames가 같은 차례로 정해 둔다.
    private void appendNestedClasses(StringBuilder sb, DtoNode root, int depth, boolean isXml,
                                     Map<DtoNode, String> classNames) {
        for (DtoNode child : root.children().values()) {
            appendNestedClass(sb, child, depth, isXml, classNames);
        }
    }

    private void appendNestedClass(StringBuilder sb, DtoNode node, int depth, boolean isXml,
                                   Map<DtoNode, String> classNames) {
        if (node.isLeaf()) {
            return;
        }
        String indent = INDENT.repeat(depth);
        sb.append("\n");
        sb.append(indent).append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        String className = classNames.get(node);
        sb.append(indent).append("public static class ").append(className).append(" {\n");
        appendClassBody(sb, node, depth + 1, isXml, classNames);
        if (!isXml) {
            appendArrayCreator(sb, node, className, depth + 1, classNames);
        }
        sb.append(indent).append("}\n");
        for (DtoNode child : node.children().values()) {
            appendNestedClass(sb, child, depth, isXml, classNames);
        }
    }

    /**
     * 리스트 필드 하나만 가진 래퍼 클래스에 배열도 받는 팩토리를 붙인다.
     * 실호출 확인 결과 에어코리아는 "items": [...] 로 배열을 바로 주고, XML 구조를 그대로
     * 옮긴 기관은 "items": {"item": [...]} 로 준다. 문서의 응답 명세표에는 컨테이너 모양이
     * 없어 어느 쪽인지 알 수 없으므로 둘 다 받는다.
     */
    private void appendArrayCreator(StringBuilder sb, DtoNode node, String className, int depth,
                                    Map<DtoNode, String> classNames) {
        List<DtoNode> children = new ArrayList<>(node.children().values());
        if (children.size() != 1 || !children.get(0).isList()) {
            return;
        }
        DtoNode listChild = children.get(0);
        String fieldName = JavaNames.camel(listChild.name());
        // 인자 이름이 아래 지역 변수 wrapper와 같으면 "already defined"로 컴파일되지 않는다
        if ("wrapper".equals(JavaNameRules.canonicalIdentifier(fieldName))) {
            throw new IllegalArgumentException(
                    "응답 필드의 Java 이름이 생성 코드의 변수와 겹칩니다: '" + listChild.name() + "' → '" + fieldName
                    + "' — IR 경로 세그먼트 이름을 바꿔 주세요.");
        }
        String indent = INDENT.repeat(depth);
        String bodyIndent = INDENT.repeat(depth + 1);
        sb.append("\n");
        sb.append(indent).append("/** \"").append(JavaText.comment(node.name()))
          .append("\"이 래퍼 객체가 아니라 배열로 오는 기관 대응 */\n");
        sb.append(indent).append("@JsonCreator\n");
        sb.append(indent).append("public static ").append(className).append(" ofArray(")
          .append(fieldType(listChild, classNames)).append(" ").append(fieldName).append(") {\n");
        sb.append(bodyIndent).append(className).append(" wrapper = new ").append(className).append("();\n");
        sb.append(bodyIndent).append("wrapper.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        sb.append(bodyIndent).append("return wrapper;\n");
        sb.append(indent).append("}\n");
    }

    // 리프면 TYPE_MAP, 내부면 assignClassNames가 정한 클래스 이름, 리스트면 List<...>로 감싼다.
    private String fieldType(DtoNode node, Map<DtoNode, String> classNames) {
        String base = node.isLeaf()
                ? TYPE_MAP.getOrDefault(node.leafType(), DEFAULT_TYPE)
                : classNames.get(node);
        return node.isList() ? "List<" + base + ">" : base;
    }
}
