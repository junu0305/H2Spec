package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.model.DtoNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DTO 트리를 Jackson XML 어노테이션이 붙은 단일 .java 소스로 방출한다. */
public class DtoEmitter {

    private static final String INDENT = "    ";
    private static final Map<String, String> TYPE_MAP = Map.of(
            "string", "String",
            "integer", "Long",
            "number", "Double",
            "boolean", "Boolean");
    private static final String DEFAULT_TYPE = "String";
    private static final String XML_FORMAT = "XML";
    private static final Set<String> RESERVED_TYPE_NAMES =
            Set.of("List", "String", "Long", "Double", "Boolean");

    public String emit(IrSpec ir, DtoNode root) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Response";
        validateNoClassNameCollisions(root, className);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(".dto;\n\n");
        boolean isXml = XML_FORMAT.equals(ir.api().responseFormat());
        appendImports(sb, root, isXml);
        sb.append("/**\n")
          .append(" * ").append(ir.api().apiName()).append(" 응답 DTO.\n")
          .append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n")
          .append(" */\n");
        sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        if (isXml) {
            sb.append("@JacksonXmlRootElement(localName = \"").append(root.name()).append("\")\n");
        } else {
            // JSON은 루트가 {"response":{...}}처럼 키로 들어온다. 클라이언트의
            // UNWRAP_ROOT_VALUE와 짝을 이뤄야 하며, 없으면 전 필드가 null인 객체가 조용히 나온다.
            sb.append("@JsonRootName(\"").append(root.name()).append("\")\n");
        }
        sb.append("public class ").append(className).append(" {\n");
        appendClassBody(sb, root, 1, isXml);
        appendNestedClasses(sb, root, 1, isXml);
        sb.append("}\n");
        return sb.toString();
    }

    // 내부 노드는 전부 루트 클래스 바로 아래 flat한 static 중첩 클래스로 방출되므로(appendNestedClass 참고),
    // 트리 전체에서 pascal(name)이 겹치는 내부 노드가 있으면 "class already defined" 컴파일 에러가 난다.
    // 루트 클래스명, 예약된 타입 이름과의 충돌도 함께 막는다.
    private void validateNoClassNameCollisions(DtoNode root, String rootClassName) {
        Map<String, String> pascalNameToPath = new LinkedHashMap<>();
        collectNestedClassNames(root, root.name(), rootClassName, pascalNameToPath);
    }

    private void collectNestedClassNames(DtoNode node, String path, String rootClassName,
                                          Map<String, String> pascalNameToPath) {
        for (DtoNode child : node.children().values()) {
            if (child.isLeaf()) {
                continue;
            }
            String childPath = path + "." + child.name();
            String pascalName = JavaNames.pascal(child.name());
            checkClassNameCollision(pascalName, childPath, rootClassName, pascalNameToPath);
            collectNestedClassNames(child, childPath, rootClassName, pascalNameToPath);
        }
    }

    private void checkClassNameCollision(String pascalName, String path, String rootClassName,
                                          Map<String, String> pascalNameToPath) {
        if (pascalName.equals(rootClassName)) {
            throw new IllegalArgumentException(
                    "DTO 클래스명 충돌: '" + pascalName + "' (" + path
                    + ") — 루트 클래스명과 겹칩니다. IR 경로 세그먼트 이름을 구분해 주세요.");
        }
        if (RESERVED_TYPE_NAMES.contains(pascalName)) {
            throw new IllegalArgumentException(
                    "DTO 클래스명 충돌: '" + pascalName + "' (" + path
                    + ") — 예약된 타입 이름과 겹칩니다. IR 경로 세그먼트 이름을 구분해 주세요.");
        }
        String existingPath = pascalNameToPath.putIfAbsent(pascalName, path);
        if (existingPath != null) {
            throw new IllegalArgumentException(
                    "DTO 클래스명 충돌: '" + pascalName + "' (" + existingPath + ", " + path
                    + ") — IR 경로 세그먼트 이름을 구분해 주세요.");
        }
    }

    // 트리에 리스트가 있으면 java.util.List + 빈 줄, 항상 JsonIgnoreProperties,
    // 리스트 있으면 ElementWrapper/Property, 원본명!=camel명인 필드가 있으면 Property, 항상 RootElement.
    // 마지막에 빈 줄.
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

    // 필드 선언 전부(javadoc, 리스트 어노테이션, localName 어노테이션 포함) → getter/setter 전부.
    // 각 선언 사이 빈 줄.
    private void appendClassBody(StringBuilder sb, DtoNode node, int depth, boolean isXml) {
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
            appendFieldDeclaration(sb, field, indent, isXml);
            prevDecorated = decorated;
        }

        sb.append("\n");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            appendGetter(sb, fields.get(i), indent, depth);
            sb.append("\n");
            appendSetter(sb, fields.get(i), indent, depth);
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

    private void appendFieldDeclaration(StringBuilder sb, DtoNode node, String indent, boolean isXml) {
        if (node.isLeaf() && node.description() != null) {
            sb.append(indent).append("/** ").append(node.description()).append(" */\n");
        }
        boolean renamed = !node.name().equals(JavaNames.camel(node.name()));
        if (!isXml) {
            // JSON 배열에는 XML의 래퍼 요소 개념이 없어 이름만 맞춰주면 된다
            if (node.isList() || renamed) {
                sb.append(indent).append("@JsonProperty(\"").append(node.name()).append("\")\n");
            }
        } else if (node.isList()) {
            sb.append(indent).append("@JacksonXmlElementWrapper(useWrapping = false)\n");
            sb.append(indent).append("@JacksonXmlProperty(localName = \"").append(node.name()).append("\")\n");
        } else if (renamed) {
            sb.append(indent).append("@JacksonXmlProperty(localName = \"").append(node.name()).append("\")\n");
        }
        sb.append(indent).append("private ").append(fieldType(node)).append(" ")
          .append(JavaNames.camel(node.name())).append(";\n");
    }

    private void appendGetter(StringBuilder sb, DtoNode node, String indent, int depth) {
        String type = fieldType(node);
        String fieldName = JavaNames.camel(node.name());
        String bodyIndent = INDENT.repeat(depth + 1);
        sb.append(indent).append("public ").append(type).append(" get")
          .append(JavaNames.pascal(node.name())).append("() {\n");
        sb.append(bodyIndent).append("return ").append(fieldName).append(";\n");
        sb.append(indent).append("}\n");
    }

    private void appendSetter(StringBuilder sb, DtoNode node, String indent, int depth) {
        String type = fieldType(node);
        String fieldName = JavaNames.camel(node.name());
        String bodyIndent = INDENT.repeat(depth + 1);
        sb.append(indent).append("public void set").append(JavaNames.pascal(node.name()))
          .append("(").append(type).append(" ").append(fieldName).append(") {\n");
        sb.append(bodyIndent).append("this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        sb.append(indent).append("}\n");
    }

    // 내부 노드 DFS 순회(pre-order), 각 노드당 "@JsonIgnoreProperties..." + "public static class Pascal(name)" 방출.
    // 중첩이 아니라 루트 바로 아래 형제로 flat하게 나열한다.
    private void appendNestedClasses(StringBuilder sb, DtoNode root, int depth, boolean isXml) {
        for (DtoNode child : root.children().values()) {
            appendNestedClass(sb, child, depth, isXml);
        }
    }

    private void appendNestedClass(StringBuilder sb, DtoNode node, int depth, boolean isXml) {
        if (node.isLeaf()) {
            return;
        }
        String indent = INDENT.repeat(depth);
        sb.append("\n");
        sb.append(indent).append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        String className = JavaNames.pascal(node.name());
        sb.append(indent).append("public static class ").append(className).append(" {\n");
        appendClassBody(sb, node, depth + 1, isXml);
        if (!isXml) {
            appendArrayCreator(sb, node, className, depth + 1);
        }
        sb.append(indent).append("}\n");
        for (DtoNode child : node.children().values()) {
            appendNestedClass(sb, child, depth, isXml);
        }
    }

    /**
     * 리스트 필드 하나만 가진 래퍼 클래스에 배열도 받는 팩토리를 붙인다.
     * 실호출 확인 결과 에어코리아는 "items": [...] 로 배열을 바로 주고, XML 구조를 그대로
     * 옮긴 기관은 "items": {"item": [...]} 로 준다. 문서의 응답 명세표에는 컨테이너 모양이
     * 없어 어느 쪽인지 알 수 없으므로 둘 다 받는다.
     */
    private void appendArrayCreator(StringBuilder sb, DtoNode node, String className, int depth) {
        List<DtoNode> children = new ArrayList<>(node.children().values());
        if (children.size() != 1 || !children.get(0).isList()) {
            return;
        }
        DtoNode listChild = children.get(0);
        String fieldName = JavaNames.camel(listChild.name());
        String indent = INDENT.repeat(depth);
        String bodyIndent = INDENT.repeat(depth + 1);
        sb.append("\n");
        sb.append(indent).append("/** \"").append(node.name())
          .append("\"이 래퍼 객체가 아니라 배열로 오는 기관 대응 */\n");
        sb.append(indent).append("@JsonCreator\n");
        sb.append(indent).append("public static ").append(className).append(" ofArray(")
          .append(fieldType(listChild)).append(" ").append(fieldName).append(") {\n");
        sb.append(bodyIndent).append(className).append(" wrapper = new ").append(className).append("();\n");
        sb.append(bodyIndent).append("wrapper.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        sb.append(bodyIndent).append("return wrapper;\n");
        sb.append(indent).append("}\n");
    }

    // 리프면 TYPE_MAP, 내부면 pascal(name), 리스트면 List<...>로 감싼다.
    private String fieldType(DtoNode node) {
        String base = node.isLeaf()
                ? TYPE_MAP.getOrDefault(node.leafType(), DEFAULT_TYPE)
                : JavaNames.pascal(node.name());
        return node.isList() ? "List<" + base + ">" : base;
    }
}
