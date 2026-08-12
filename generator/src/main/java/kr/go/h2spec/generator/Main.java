package kr.go.h2spec.generator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("사용법: <ir.json 경로> <출력 디렉터리>");
            System.exit(1);
        }
        List<Path> written = new CodeGenerator().generate(Path.of(args[0]), Path.of(args[1]));
        written.forEach(path -> System.out.println("생성: " + path));
    }
}
