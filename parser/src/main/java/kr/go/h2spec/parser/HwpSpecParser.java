package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 공공데이터포털 표준 기술문서(HWP)를 읽어 상세기능(오퍼레이션)별 IR JSON을 추출한다.
 * <p>
 * 문서 구조 해석은 {@link DocxSpecParser}와 같이 {@link SpecBlockAssembler}가 맡는다: "상세기능정보" → 키/값 표
 * (Call Back URL 포함), "요청 메시지 명세" → 파라미터 표, "응답 메시지 명세" → 응답 필드 표를 기준으로 하되
 * 기관마다 다른 구간 제목·머리행·표 배치를 함께 읽는다.
 */
public class HwpSpecParser {

    private List<String> skipped = List.of();

    public List<ParsedApi> parse(Path hwp) throws IOException {
        List<Block> blocks = new HwpSpecReader().read(hwp);
        SpecBlockAssembler assembler = new SpecBlockAssembler();
        List<ParsedApi> result = assembler.parse(blocks, hwp.getFileName().toString(), "HWP");
        skipped = assembler.skipped();

        if (result.isEmpty()) {
            throw new IllegalArgumentException(SpecBlockAssembler.notFoundMessage(hwp.getFileName().toString(), skipped));
        }
        return result;
    }

    /** 마지막 parse에서 주소나 응답 필드를 찾지 못해 건너뛴 오퍼레이션과 그 이유 */
    public List<String> skipped() {
        return skipped;
    }
}
