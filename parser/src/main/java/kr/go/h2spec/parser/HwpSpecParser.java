package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 공공데이터포털 표준 기술문서(HWP)를 읽어 상세기능(오퍼레이션)별 IR JSON을 추출한다.
 * <p>
 * 문서 구조 해석은 {@link DocxSpecParser}와 같이 {@link SpecBlockAssembler}가 맡는다: "상세기능정보" → 키/값 표
 * (Call Back URL 포함), "요청 메시지 명세" → 파라미터 표, "응답 메시지 명세" → 응답 필드 표를 기준으로 하되
 * 기관마다 다른 구간 제목·머리행·표 배치를 함께 읽는다.
 * <p>
 * 확장자와 상관없이 HWP로 읽는 기존 API를 지키는 래퍼다. 조립은 {@link RuleSpecParser}와 같은 코드가 한다.
 * 엔진을 가리지 않는 새 코드는 {@link SpecParser}를 쓴다.
 */
public class HwpSpecParser {

    private List<String> skipped = List.of();

    /**
     * 문서를 HWP로 읽어 오퍼레이션별 IR을 만든다.
     *
     * @param hwp 읽을 문서. 확장자는 보지 않는다
     * @return 변환한 오퍼레이션의 IR (문서 순서)
     * @throws IOException              문서를 읽지 못했을 때
     * @throws IllegalArgumentException 변환한 오퍼레이션이 없을 때. 건너뛴 이유는 메시지와 {@link #skipped()}로 알린다
     */
    public List<ParsedApi> parse(Path hwp) throws IOException {
        SpecParseResult result = RuleSpecParser.assemble(hwp, new HwpSpecReader(), "HWP");
        skipped = result.skipped();

        if (result.apis().isEmpty()) {
            throw new IllegalArgumentException(SpecBlockAssembler.notFoundMessage(hwp.getFileName().toString(), skipped));
        }
        // 지금까지처럼 호출한 쪽이 고칠 수 있는 목록으로 돌려준다
        return new ArrayList<>(result.apis());
    }

    /** 마지막 parse에서 주소나 응답 필드를 찾지 못해 건너뛴 오퍼레이션과 그 이유 */
    public List<String> skipped() {
        return skipped;
    }
}
