package kr.go.h2spec.eval;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.DocxSpecReader;
import kr.go.h2spec.parser.HwpSpecReader;
import kr.go.h2spec.parser.HwpxSpecReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 확장자로 Reader와 sourceFormat을 고른다. 0-1의 {@code DocumentReaders}가 생기면 그것으로 바꾼다.
 * 지금은 같은 분기가 {@code ConvertCommand}와 파서 3종에 흩어져 있어 여기서도 따로 둔다.
 */
public final class Documents {

    private Documents() {
    }

    /**
     * 평가할 수 있는 명세 문서인지 본다.
     *
     * @param path 파일 경로
     * @return 확장자가 .docx, .hwp, .hwpx(대소문자 무시)면 true
     */
    public static boolean isDocument(Path path) {
        return sourceFormat(path) != null;
    }

    /**
     * IR metadata에 적을 원본 포맷.
     *
     * @param path 문서 경로
     * @return DOCX, HWP, HWPX. 명세 문서가 아니면 null
     */
    public static String sourceFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return "DOCX";
        }
        if (name.endsWith(".hwpx")) {
            return "HWPX";
        }
        if (name.endsWith(".hwp")) {
            return "HWP";
        }
        return null;
    }

    /**
     * 문서를 Block 목록으로 읽는다.
     *
     * @param path 문서 경로
     * @return 문서 순서 그대로의 블록
     * @throws IOException 파일을 읽지 못했을 때
     */
    public static List<Block> read(Path path) throws IOException {
        String format = sourceFormat(path);
        if (format == null) {
            throw new IllegalArgumentException("명세 문서(.docx, .hwp, .hwpx)가 아닙니다: " + path);
        }
        return switch (format) {
            case "DOCX" -> new DocxSpecReader().read(path);
            case "HWPX" -> new HwpxSpecReader().read(path);
            default -> new HwpSpecReader().read(path);
        };
    }
}
