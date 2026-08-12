package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 문서의 상세기능(오퍼레이션) 하나를 변환한 IR JSON. */
public record ParsedApi(String apiId, ObjectNode ir) {
}
