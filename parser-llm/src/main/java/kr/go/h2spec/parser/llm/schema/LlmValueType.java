package kr.go.h2spec.parser.llm.schema;

/** 요청 파라미터와 응답 필드의 값 타입. 정규화가 소문자로 바꿔 IR의 {@code type}으로 옮긴다. */
public enum LlmValueType { STRING, INTEGER, NUMBER, BOOLEAN }
