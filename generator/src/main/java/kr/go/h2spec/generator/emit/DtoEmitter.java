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
     * 생성 DTO가 이름으로 부르는 타입(필드 타입과 import하는 어노테이션). 중첩 클래스 이름은 루트 클래스 본문 전체에서
     * 같은 이름의 import를 가리므로, 중첩 클래스가 이 이름이면 List 필드나 어노테이션이 그 클래스를 가리켜 컴파일되지 않고
     * String 같은 필드는 엉뚱한 타입이 된다. 포맷마다 import가 다르지만 같은 IR이 포맷과 상관없이 같은 클래스 이름을 갖도록
     * 두 포맷의 이름을 모두 피한다. TYPE_MAP이나 appendImports에 타입을 더하면 여기에도 더한다.
     */
    private static final Set<String> RESERVED_TYPE_NAMES = Set.of(
            "List", "String", "Long", "Double", "Boolean",
            "JsonIgnoreProperties", "JsonProperty", "JsonCreator", "JsonRootName",
            "JacksonXmlRootElement", "JacksonXmlProperty", "JacksonXmlElementWrapper");

    public String emit(IrSpec ir, DtoNode root) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Response";
        Map<DtoNode, String> classNames = assignClassNames(root, className);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(".dto;\n\n");
        boolean isXml = XML_FORMAT.equals(ir.api().responseFormat());
        appendImports(sb, root, isXml);
        sb.append("/**\n")
          .append(" * ").append(JavaText.comment(ir.api().apiName())).append(" 응답 DTO.\n")
          .append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n")
          .append(" */\n");
        sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        // TODO(루트 여럿): 루트 없는 응답이면 루트 어노테이션을 달지 않아야 한다. 자세한 내용은 DtoTreeBuilder.build
        if (isXml) {
            sb.append("@JacksonXmlRootElement(localName = \"").append(JavaText.literal(root.name())).append("\")\n");
        } else {
            // JSON은 루트가 {"response":{...}}처럼 키로 들어온다. 클라이언트의
            // UNWRAP_ROOT_VALUE와 짝을 이뤄야 하며, 없으면 전 필드가 null인 객체가 조용히 나온다.
            sb.append("@JsonRootName(\"").append(JavaText.literal(root.name())).append("\")\n");
        }
        sb.append("public class ").append(className).append(" {\n");
        appendClassBody(sb, root, 1, isXml, classNames);
        appendNestedClasses(sb, root, 1, isXml, classNames);
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
     *       루트의 자식은 루트 클래스 대신 루트 요소 이름을 붙인다. 부모 이름도 겹치지 않게 정했으므로 대개 여기서 끝난다</li>
     *   <li>그래도 겹치면 pascal(이름) 뒤에 2부터 차례로 번호를 붙인다(List2)</li>
     * </ol>
     * 필드 이름과 어노테이션은 원래 이름을 그대로 쓰므로 클래스 이름이 바뀌어도 JSON·XML 매핑은 같다.
     */
    private Map<DtoNode, String> assignClassNames(DtoNode root, String rootClassName) {
        Map<DtoNode, String> classNames = new IdentityHashMap<>();
        assignNestedClassNames(root, JavaNames.pascal(root.name()), rootClassName, classNames, new HashSet<>());
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

    // 트리에 리스트가 있으면 java.util.List + 빈 줄, 항상 JsonIgnoreProperties. XML은 리스트 있으면 ElementWrapper/Property,
    // 원본명!=camel명인 필드가 있으면 Property, 항상 RootElement. JSON은 리스트나 원본명!=camel명인 필드가 있으면
    // JsonProperty, 리스트 래퍼가 있으면 JsonCreator, 항상 JsonRootName. 마지막에 빈 줄.
    private void appendImports(StringBuilder sb, DtoNode root, boolean isXml) {
        boolean hasList = hasList(root);
        boolean hasRenamedField = hasRenamedField(root);
        if (hasList) {
            sb.append("import java.util.List;\n\n");
        }
        sb.append("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;\n");
        if (!isXml) {
            if (hasList || hasRenamedField) {
                sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
            }
            if (hasListWrapper(root)) {
                sb.append("import com.fasterxml.jackson.annotation.JsonCreator;\n");
            }
            sb.append("import com.fasterxml.jackson.annotation.JsonRootName;\n");
            sb.append("\n");
            return;
        }
        if (hasList) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;\n");
        }
        if (hasList || hasRenamedField) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;\n");
        }
        sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;\n");
        sb.append("\n");
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
            if (!child.name().equals(JavaNames.camel(child.name())) || hasRenamedField(child)) {
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
            appendGetter(sb, fields.get(i), indent, depth, classNames);
            sb.append("\n");
            appendSetter(sb, fields.get(i), indent, depth, classNames);
        }
    }

    private boolean isDecorated(DtoNode node) {
        if (node.isLeaf() && node.description() != null) {
            return true;
        }
        if (node.isList()) {
            return true;
        }
        return !node.name().equals(JavaNames.camel(node.name()));
    }

    private void appendFieldDeclaration(StringBuilder sb, DtoNode node, String indent, boolean isXml,
                                        Map<DtoNode, String> classNames) {
        if (node.isLeaf() && node.description() != null) {
            sb.append(indent).append("/** ").append(JavaText.comment(node.description())).append(" */\n");
        }
        boolean renamed = !node.name().equals(JavaNames.camel(node.name()));
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
        sb.append(indent).append("private ").append(fieldType(node, classNames)).append(" ")
          .append(JavaNames.camel(node.name())).append(";\n");
    }

    private void appendGetter(StringBuilder sb, DtoNode node, String indent, int depth,
                              Map<DtoNode, String> classNames) {
        String type = fieldType(node, classNames);
        String fieldName = JavaNames.camel(node.name());
        String bodyIndent = INDENT.repeat(depth + 1);
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
