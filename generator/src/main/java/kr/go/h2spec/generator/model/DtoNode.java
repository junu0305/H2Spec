package kr.go.h2spec.generator.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** responseFields[].path를 분해해 만든 DTO 클래스 트리의 노드. */
public final class DtoNode {

    private final String name;
    private final boolean list;
    private final Map<String, DtoNode> children = new LinkedHashMap<>();
    private String leafType;
    private String description;

    DtoNode(String name, boolean list) {
        this.name = name;
        this.list = list;
    }

    public String name() {
        return name;
    }

    public boolean isList() {
        return list;
    }

    public String leafType() {
        return leafType;
    }

    public String description() {
        return description;
    }

    public boolean isLeaf() {
        return leafType != null;
    }

    public Map<String, DtoNode> children() {
        return children;
    }

    public DtoNode child(String name) {
        return children.get(name);
    }

    void markLeaf(String type, String description, String fullPath) {
        if (!children.isEmpty()) {
            throw new IllegalArgumentException("IR 응답 경로 충돌 (리프이면서 내부 노드): " + fullPath);
        }
        this.leafType = type;
        this.description = description;
    }

    DtoNode getOrCreateChild(String name, boolean list, String fullPath) {
        if (isLeaf()) {
            throw new IllegalArgumentException("IR 응답 경로 충돌 (리프이면서 내부 노드): " + fullPath);
        }
        DtoNode existing = children.get(name);
        if (existing != null) {
            if (existing.isList() != list) {
                throw new IllegalArgumentException("IR 응답 경로 충돌 (리스트 표기 불일치): " + fullPath);
            }
            return existing;
        }
        DtoNode newNode = new DtoNode(name, list);
        children.put(name, newNode);
        return newNode;
    }
}
