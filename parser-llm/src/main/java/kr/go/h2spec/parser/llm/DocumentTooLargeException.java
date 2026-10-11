package kr.go.h2spec.parser.llm;

/**
 * 직렬화한 문서가 {@link DocumentSerializer}의 길이 상한을 넘었다.
 * 잘라서 보내면 뒤쪽 오퍼레이션과 필드가 조용히 빠지므로 자르지 않고 알린다.
 */
public class DocumentTooLargeException extends IllegalArgumentException {

    private final int length;
    private final int maxLength;

    DocumentTooLargeException(int length, int maxLength) {
        super("직렬화한 문서가 길이 상한을 넘습니다: " + length + "자 (상한 " + maxLength + "자). "
                + "자르면 필드가 빠지므로 LLM에 보내지 않습니다");
        this.length = length;
        this.maxLength = maxLength;
    }

    /** @return 직렬화한 텍스트의 글자 수 */
    public int length() {
        return length;
    }

    /** @return 넘어선 길이 상한 */
    public int maxLength() {
        return maxLength;
    }
}
