package kr.go.h2spec.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** h2spec CLI 진입점. */
@Command(
        name = "h2spec",
        description = "공공데이터 API 명세를 OpenAPI 스펙과 Spring 클라이언트 코드로 변환한다",
        mixinStandardHelpOptions = true,
        versionProvider = H2SpecCli.ManifestVersion.class,
        subcommands = ConvertCommand.class)
public class H2SpecCli {

    public static void main(String[] args) {
        System.exit(new CommandLine(new H2SpecCli()).execute(args));
    }

    /** 배포판의 jar 매니페스트에서 버전을 읽는다. IDE나 클래스 디렉터리 실행에서는 매니페스트가 없다. */
    static class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = H2SpecCli.class.getPackage().getImplementationVersion();
            return new String[] {"h2spec " + (version != null ? version : "(개발 빌드)")};
        }
    }
}
