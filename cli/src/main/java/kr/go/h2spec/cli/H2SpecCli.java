package kr.go.h2spec.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** h2spec CLI 진입점. */
@Command(
        name = "h2spec",
        description = "공공데이터 API 명세를 OpenAPI 스펙과 Spring 클라이언트 코드로 변환한다",
        mixinStandardHelpOptions = true,
        subcommands = ConvertCommand.class)
public class H2SpecCli {

    public static void main(String[] args) {
        System.exit(new CommandLine(new H2SpecCli()).execute(args));
    }
}
