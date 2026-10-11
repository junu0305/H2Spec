package kr.go.h2spec.eval;

import java.nio.file.Path;

/**
 * 테스트가 읽는 세트 A 문서의 위치. 문서는 parser 테스트 리소스 그 자체라 복사하지 않고 저장소 루트에서 읽는다.
 * Gradle은 저장소 루트를 시스템 속성으로 넘기고(llm-eval/build.gradle), IDE에서 모듈 디렉터리로 돌리면 그 부모를 쓴다.
 */
public final class TestPaths {

    private TestPaths() {
    }

    /** @return 저장소 루트 */
    public static Path repoRoot() {
        String root = System.getProperty("h2spec.repoRoot");
        return root != null ? Path.of(root) : Path.of("").toAbsolutePath().getParent();
    }

    /**
     * corpus 문서나 정답 파일.
     *
     * @param name 파일 이름 (jeju-folksong.hwp)
     * @return 경로
     */
    public static Path corpus(String name) {
        return repoRoot().resolve(DataSets.CORPUS).resolve(name);
    }
}
