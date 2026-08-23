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
    private static final Set<String> RESERVED_TYPE_NAMES =
            Set.of("List", "String", "Long", "Double", "Boolean");

    public String emit(IrSpec ir, DtoNode root) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Response";
        validateNoClassNameCollisions(root, className);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(".dto;\n\n");
        appendImports(sb, root);
        sb.append("/**\n")
          .append(" * ").append(ir.api().apiName()).append(" 응답 DTO.\n")
          .append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n")
          .append(" */\n");
        sb.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        sb.append("@JacksonXmlRootElement(localName = \"").append(root.name()).append("\")\n");
        sb.append("public class ").append(className).append(" {\n");
        appendClassBody(sb, root, 1);
        appendNestedClasses(sb, root, 1);
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
    private void appendImports(StringBuilder sb, DtoNode root) {
        boolean hasList = hasList(root);
        boolean hasRenamedField = hasRenamedField(root);
        if (hasList) {
            sb.append("import java.util.List;\n\n");
        }
        sb.append("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;\n");
        if (hasList) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;\n");
        }
        if (hasList || hasRenamedField) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;\n");
        }
        sb.append("import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;\n");
        sb.append("\n");
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
    private void appendClassBody(StringBuilder sb, DtoNode node, int depth) {
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
            appendFieldDeclaration(sb, field, indent);
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

    private void appendFieldDeclaration(StringBuilder sb, DtoNode node, String indent) {
        if (node.isLeaf() && node.description() != null) {
            sb.append(indent).append("/** ").append(node.description()).append(" */\n");
        }
        if (node.isList()) {
            sb.append(indent).append("@JacksonXmlElementWrapper(useWrapping = false)\n");
            sb.append(indent).append("@JacksonXmlProperty(localName = \"").append(node.name()).append("\")\n");
        } else if (!node.name().equals(JavaNames.camel(node.name()))) {
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
    private void appendNestedClasses(StringBuilder sb, DtoNode root, int depth) {
        for (DtoNode child : root.children().values()) {
            appendNestedClass(sb, child, depth);
        }
    }

    private void appendNestedClass(StringBuilder sb, DtoNode node, int depth) {
        if (node.isLeaf()) {
            return;
        }
        String indent = INDENT.repeat(depth);
        sb.append("\n");
        sb.append(indent).append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        sb.append(indent).append("public static class ").append(JavaNames.pascal(node.name())).append(" {\n");
        appendClassBody(sb, node, depth + 1);
        sb.append(indent).append("}\n");
        for (DtoNode child : node.children().values()) {
            appendNestedClass(sb, child, depth);
        }
    }

    // 리프면 TYPE_MAP, 내부면 pascal(name), 리스트면 List<...>로 감싼다.
    private String fieldType(DtoNode node) {
        String base = node.isLeaf()
                ? TYPE_MAP.getOrDefault(node.leafType(), DEFAULT_TYPE)
                : JavaNames.pascal(node.name());
        return node.isList() ? "List<" + base + ">" : base;
    }
}
