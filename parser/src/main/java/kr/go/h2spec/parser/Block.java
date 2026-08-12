package kr.go.h2spec.parser;

import java.util.List;

/** DOCX 본문을 순서대로 평탄화한 블록 — 문단(제목) 또는 표. */
public sealed interface Block {

    record Heading(String text) implements Block {
    }

    record Table(List<List<String>> rows) implements Block {
    }
}
