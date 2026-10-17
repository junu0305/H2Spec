package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 확장자(.docx, .hwp, .hwpx, 대소문자 무시)로 문서의 Reader와 IR metadata에 적을 sourceFormat을 정한다.
 * <p>
 * 두 엔진과 CLI가 같은 대응을 쓰도록 한 곳에 둔다. 엔진마다 따로 두면 같은 문서가 엔진에 따라 다른 sourceFormat으로
 * 적히거나 CLI만 문서로 보는 확장자가 생긴다. 여기 없는 확장자를 IR JSON으로 보는 것은 CLI가 정한다.
 */
public final class DocumentReaders {

    /** Reader는 상태가 없어 형식마다 하나를 같이 쓴다 */
    private static final Map<String, Format> BY_EXTENSION = Map.of(
            ".docx", new Format("DOCX", new DocxSpecReader()),
            ".hwp", new Format("HWP", new HwpSpecReader()),
            ".hwpx", new Format("HWPX", new HwpxSpecReader()));

    private DocumentReaders() {
    }

    /**
     * 문서 형식 하나.
     *
     * @param sourceFormat IR metadata의 sourceFormat ("DOCX", "HWP", "HWPX")
     * @param reader       그 형식을 읽는 Reader
     */
    public record Format(String sourceFormat, DocumentReader reader) {

        /**
         * 이 형식의 Reader로 문서를 읽는다. 문서 라이브러리(POI, hwplib)가 손상되었거나 지원하지 않는 내용에서 던지는 RuntimeException도
         * 문서를 읽지 못한 것으로 보고 IOException으로 바꾼다. 디렉터리를 변환할 때 그 문서 하나 때문에 실행 전체가 멈추지 않게 한다.
         *
         * @param document 읽을 문서
         * @return 문서 순서대로의 문단·표 블록
         * @throws IOException 파일을 읽지 못했거나 문서 라이브러리가 해석하지 못했을 때
         */
        public List<Block> read(Path document) throws IOException {
            try {
                return reader.read(document);
            } catch (RuntimeException e) {
                String detail = e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + e.getMessage();
                throw new IOException("문서를 해석하지 못했습니다 (" + e.getClass().getSimpleName() + detail + ")", e);
            }
        }
    }

    /**
     * 파일 이름의 확장자로 문서 형식을 찾는다. 파일이 있는지나 내용은 보지 않는다.
     *
     * @param path 파일 경로
     * @return 명세 문서면 그 형식, 아니면 빈 값
     */
    public static Optional<Format> find(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? Optional.empty() : Optional.ofNullable(BY_EXTENSION.get(name.substring(dot)));
    }

    /**
     * 확장자로 고른 Reader로 문서를 읽는다({@link Format#read}).
     *
     * @param document 읽을 문서
     * @return 문서 순서대로의 문단·표 블록
     * @throws IOException              파일을 읽지 못했거나 문서 라이브러리가 해석하지 못했을 때
     * @throws IllegalArgumentException 명세 문서(.docx, .hwp, .hwpx)가 아닐 때
     */
    public static List<Block> read(Path document) throws IOException {
        Format format = find(document).orElseThrow(
                () -> new IllegalArgumentException("명세 문서(.docx, .hwp, .hwpx)가 아닙니다: " + document.getFileName()));
        return format.read(document);
    }

    /**
     * 명세 문서인지 본다.
     *
     * @param path 파일 경로
     * @return 확장자가 .docx, .hwp, .hwpx 가운데 하나면(대소문자 무시) true
     */
    public static boolean isDocument(Path path) {
        return find(path).isPresent();
    }
}
