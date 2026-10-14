package kr.go.h2spec.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.ParsedApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConvertCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void IR_JSON을_산출물_3종으로_변환한다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(tempDir.resolve("openapi/RTMSDataSvcAptTradeDev.json")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/dto/RTMSDataSvcAptTradeDevResponse.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")));
    }

    @Test
    void success_code_옵션이_성공코드를_덮어쓴다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--success-code", "99");

        assertEquals(0, exit);
        String client = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java"));
        assertTrue(client.contains("SUCCESS_CODE = \"99\""));
    }

    @Test
    void success_code_옵션은_결과코드_위치_후보를_그대로_둔다() throws Exception {
        // 성공코드만 바꿔야 한다. 후보까지 지우면 문서에 적힌 결과코드 이름(resultCd)을 인터셉터에 넘기지 못한다
        ObjectNode tree = readIr(irPath());
        tree.withArray("/api/errorSpec/resultCodeFieldCandidates").insert(0, "response.header.resultCd");
        Path ir = Files.createDirectories(tempDir.resolve("input")).resolve("result-cd.json");
        Files.writeString(ir, tree.toPrettyString());
        Path output = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", ir.toString(), "--output", output.toString(), "--success-code", "0");

        assertEquals(0, exit);
        String client = Files.readString(output.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java"));
        assertTrue(client.contains("SUCCESS_CODE = \"0\""), client);
        assertTrue(client.contains("new PublicDataErrorInterceptor(SUCCESS_CODE, List.of(\"resultCd\"))"), client);
    }

    @Test
    void format_옵션이_응답_포맷을_덮어쓴다() throws Exception {
        // 샘플 문서는 대부분 기본값이 xml이라, 이 옵션이 없으면 JSON 클라이언트를 만들 방법이 없다
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--format", "json");

        assertEquals(0, exit);
        String client = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java"));
        String dto = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/dto/RTMSDataSvcAptTradeDevResponse.java"));
        assertTrue(client.contains("objectMapper.readValue"));
        assertFalse(client.contains("XmlMapper"));
        assertFalse(dto.contains("JacksonXml"));
    }

    @Test
    void package_옵션이_생성_패키지를_덮어쓴다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        // apiId(RTMSDataSvcAptTradeDev)를 소문자화한 하위 패키지가 자동으로 붙는다
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/dto/RTMSDataSvcAptTradeDevResponse.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/RTMSDataSvcAptTradeDevClient.java")));
        assertFalse(Files.exists(tempDir.resolve("kr/go/h2spec/client/landmolit")));
    }

    @Test
    void package_옵션이_DOCX_변환에도_적용되고_오퍼레이션별로_하위_패키지가_나뉜다() throws Exception {
        Path docx = Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", tempDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        // 측정소정보 문서의 오퍼레이션(측정소 목록 / TM 기준좌표 / 근접측정소 목록)이 각자 하위 패키지로 분리된다
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void format_옵션에_잘못된_값을_주면_한국어_메시지로_실패한다() throws Exception {
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", irPath().toString(),
                "--output", tempDir.toString(), "--format", "yaml");

        assertEquals(1, exit);
        assertTrue(err.toString().contains("xml"), err.toString());
    }

    @Test
    void DOCX_명세서를_오퍼레이션별_산출물로_변환한다() throws Exception {
        Path docx = Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", tempDir.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(tempDir.resolve("ir/MsrstnList.json")));
        assertTrue(Files.exists(tempDir.resolve("openapi/MsrstnList.json")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 디렉터리를_입력하면_안의_명세_파일을_전부_변환한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI()),
                inputDir.resolve("msrstn-info.docx"));
        // 명세 파일이 아닌 파일은 무시되어야 한다
        Files.writeString(inputDir.resolve("README.md"), "관련 없는 파일");
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString());

        assertEquals(0, exit);
        // JSON IR 한 건
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")));
        // DOCX(오퍼레이션 여러 개 포함) 한 건
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 디렉터리_입력에도_package_옵션이_모든_파일에_적용된다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI()),
                inputDir.resolve("msrstn-info.docx"));
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        assertTrue(Files.exists(outputDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/RTMSDataSvcAptTradeDevClient.java")));
        assertTrue(Files.exists(outputDir.resolve(
                "com/example/publicdata/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 변환할_명세_파일이_없는_디렉터리는_한국어_메시지로_실패한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("empty-input"));
        Files.writeString(inputDir.resolve("readme.txt"), "명세 파일 아님");
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("output").toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("찾지 못했습니다"), err.toString());
    }

    @Test
    void 디렉터리_안에_손상된_파일이_있어도_나머지_파일은_변환되고_결과는_실패로_보고된다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.writeString(inputDir.resolve("broken.json"), "{ not valid json");
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString());

        assertEquals(1, exit, "일부 파일이 실패하면 디렉터리 변환 전체 종료 코드는 1이어야 한다");
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")),
                "실패한 파일이 있어도 나머지 파일은 계속 변환되어야 한다");
    }

    @Test
    void 같은_apiId를_만드는_문서가_겹치면_경고한다() throws Exception {
        // 배치는 모든 문서를 한 출력 디렉터리에 쏟으므로 apiId가 겹치면 앞선 산출물이 사라진다
        Path inputDir = Files.createDirectories(tempDir.resolve("dup-input"));
        Files.copy(irPath(), inputDir.resolve("a.json"));
        Files.copy(irPath(), inputDir.resolve("b.json"));
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("dup-output").toString());

        assertEquals(0, exit);
        assertTrue(err.toString().contains("RTMSDataSvcAptTradeDev"),
                "덮어쓴 apiId를 알려야 한다: " + err);
    }

    @Test
    void path_파라미터가_있는_IR도_산출물로_변환한다() throws Exception {
        // IR을 직접 입력해도 path 파라미터는 쿼리가 아니라 주소의 {이름} 자리에 들어간다
        Path ir = Path.of(getClass().getResource("/ir/path-parameter-example.json").toURI());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", ir.toString(), "--output", tempDir.toString());

        assertEquals(0, exit);
        String client = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/itemdetail/ItemDetailClient.java"));
        assertTrue(client.contains("{itemId}"), "URL 템플릿을 치환해야 한다: " + client);
        assertFalse(client.contains("&itemId="), "path 파라미터를 쿼리로 붙이면 안 된다");
    }

    @Test
    void HWPX_명세서를_변환한다() throws Exception {
        Path hwpx = Path.of(getClass().getResource("/docs/msrstn-info.hwpx").toURI());
        Path output = tempDir.resolve("hwpx-output");
        CommandLine cmd = new CommandLine(new H2SpecCli());

        int exit = cmd.execute("convert", "--input", hwpx.toString(), "--output", output.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(output.resolve("openapi/MsrstnList.json")));
        String client = Files.readString(
                output.resolve("kr/go/h2spec/client/msrstnlist/MsrstnListClient.java"));
        assertTrue(client.contains("getMsrstnList"), client);
    }

    @Test
    void 열리지_않는_문서는_실패_이유를_알린다() throws Exception {
        Path hwp = tempDir.resolve("명세서.hwp");
        Files.writeString(hwp, "dummy");
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new H2SpecCli());
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute("convert", "--input", hwp.toString(), "--output", tempDir.toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("명세서.hwp"), err.toString());
    }

    @Test
    void 없는_입력_파일은_한국어_메시지로_실패한다() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new H2SpecCli());
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute("convert", "--input", "없는파일.json", "--output", tempDir.toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("입력 파일"));
    }

    @Test
    void 생성에_실패한_오퍼레이션은_건너뛴_오퍼레이션으로_알리고_뒤_오퍼레이션은_계속_생성한다() throws Exception {
        StringWriter err = new StringWriter();
        ConvertCommand command = parsedCommand(err);

        boolean converted = command.convertDocument(
                List.of(syntheticApi("FirstApi"), brokenApi("BrokenApi"), syntheticApi("LastApi")),
                List.of(), Path.of("synthetic.hwp"));

        assertTrue(converted, "일부 오퍼레이션만 실패한 문서는 성공으로 센다: " + err);
        assertTrue(err.toString().contains(
                "건너뛴 오퍼레이션 (synthetic.hwp): BrokenApi: IR 필수 필드 누락: api.baseUrl"), err.toString());
        assertTrue(Files.exists(tempDir.resolve("kr/go/h2spec/client/firstapi/FirstApiClient.java")));
        assertTrue(Files.exists(tempDir.resolve("kr/go/h2spec/client/lastapi/LastApiClient.java")),
                "실패한 오퍼레이션 뒤의 오퍼레이션도 생성해야 한다");
        assertTrue(Files.exists(tempDir.resolve("ir/BrokenApi.json")), "실패한 오퍼레이션의 IR은 검수하도록 남긴다");
    }

    @Test
    void 파서가_건너뛴_오퍼레이션은_생성에_실패한_문서에서도_알린다() throws Exception {
        StringWriter err = new StringWriter();
        ConvertCommand command = parsedCommand(err);

        boolean converted = command.convertDocument(List.of(brokenApi("BrokenApi")),
                List.of("오퍼레이션 2: 응답 필드 표를 찾지 못했습니다"), Path.of("synthetic.hwp"));

        assertFalse(converted, "코드를 생성한 오퍼레이션이 없으면 문서는 실패다");
        assertTrue(err.toString().contains(
                "건너뛴 오퍼레이션 (synthetic.hwp): 오퍼레이션 2: 응답 필드 표를 찾지 못했습니다"), err.toString());
        assertTrue(err.toString().contains(
                "건너뛴 오퍼레이션 (synthetic.hwp): BrokenApi: IR 필수 필드 누락: api.baseUrl"), err.toString());
    }

    @Test
    void 모든_오퍼레이션의_생성에_실패한_문서는_실패로_끝나지만_IR은_모두_남긴다() throws Exception {
        // 농사로 문서는 두 오퍼레이션 모두 serviceKey가 아닌 apiKey로 인증해 generator가 받지 못한다
        Path hwp = docPath("nongsaro-eco-farming.hwp");
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", hwp.toString(), "--output", tempDir.toString());

        assertEquals(1, exit);
        assertTrue(Files.exists(tempDir.resolve("ir/SelectAreaInfoLst.json")));
        assertTrue(Files.exists(tempDir.resolve("ir/EvrfrndFarmngLst.json")),
                "앞 오퍼레이션이 실패해도 뒤 오퍼레이션의 IR까지 써야 한다");
        assertTrue(err.toString().contains("건너뛴 오퍼레이션 (nongsaro-eco-farming.hwp): SelectAreaInfoLst: "),
                err.toString());
        assertTrue(err.toString().contains("건너뛴 오퍼레이션 (nongsaro-eco-farming.hwp): EvrfrndFarmngLst: "),
                err.toString());
        assertTrue(err.toString().contains("코드를 생성한 오퍼레이션이 없습니다"), err.toString());
    }

    @Test
    void 디렉터리_안에_코드를_하나도_만들지_못한_문서가_있으면_결과는_실패로_보고된다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(docPath("msrstn-info.docx"), inputDir.resolve("msrstn-info.docx"));
        Files.copy(docPath("nongsaro-eco-farming.hwp"), inputDir.resolve("nongsaro-eco-farming.hwp"));
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(new StringWriter()));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("output").toString());

        assertEquals(1, exit);
        assertTrue(out.toString().contains("2개 중 1개 변환 성공, 1개 실패"), out.toString());
    }

    @Test
    void 덮어쓰기_옵션을_문서에서_추출한_IR에도_반영하고_검수_노트를_남긴다() throws Exception {
        Path docx = docPath("msrstn-info.docx");
        Path plainOutput = tempDir.resolve("plain");
        Path overriddenOutput = tempDir.resolve("overridden");
        new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", plainOutput.toString());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", overriddenOutput.toString(),
                "--success-code", "99", "--format", "json", "--package", "com.example.publicdata");

        assertEquals(0, exit);
        // MsrstnList는 파서가 남긴 검수 노트가 이미 있고, NearbyMsrstnList는 검수할 것이 없던 IR이다
        for (String apiId : List.of("MsrstnList", "NearbyMsrstnList")) {
            ObjectNode plain = readIr(plainOutput.resolve("ir/" + apiId + ".json"));
            ObjectNode overridden = readIr(overriddenOutput.resolve("ir/" + apiId + ".json"));
            String parserPackage = plain.at("/generatorHints/targetPackage").asText();
            String cliPackage = "com.example.publicdata." + apiId.toLowerCase(Locale.ROOT);

            // 옵션이 바꾼 값과 노트 말고는 옵션 없이 쓴 IR과 같아야 한다. generator가 모르는 metadata 등도 그대로 남는다
            ObjectNode expected = plain.deepCopy();
            expected.withObject("/api/errorSpec").put("successResultCode", "99");
            expected.withObject("/api").put("responseFormat", "JSON");
            expected.withObject("/generatorHints").put("targetPackage", cliPackage);
            expected.withArray("/metadata/reviewNotes")
                    .add("[CLI] --success-code로 성공코드를 바꿈: 00 → 99")
                    .add("[CLI] --format으로 응답 포맷을 바꿈: XML → JSON")
                    .add("[CLI] --package로 생성 패키지를 바꿈: " + parserPackage + " → " + cliPackage);
            ObjectNode metadata = expected.withObject("/metadata");
            metadata.put("manualReviewRequired", true);
            // 변환 시각은 실행마다 다르다
            metadata.put("parsedAt", overridden.at("/metadata/parsedAt").asText());
            assertEquals(expected, overridden, apiId);
        }
    }

    @Test
    void 덮어쓰기_옵션을_IR에_써도_생성_코드는_그대로이고_그_IR에서는_옵션_없이_같은_코드가_나온다() throws Exception {
        Path docx = docPath("msrstn-info.docx");
        Path plainOutput = tempDir.resolve("plain");
        Path fromDocument = tempDir.resolve("from-document");
        Path fromPlainIr = tempDir.resolve("from-plain-ir");
        Path fromWrittenIr = tempDir.resolve("from-written-ir");
        new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", plainOutput.toString());

        int documentExit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", fromDocument.toString(),
                "--success-code", "99", "--format", "json", "--package", "com.example.publicdata");
        // 옵션 없이 추출한 IR을 입력으로 주면 옵션을 생성할 때만 적용한다
        int plainIrExit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", plainOutput.resolve("ir").toString(), "--output", fromPlainIr.toString(),
                "--success-code", "99", "--format", "json", "--package", "com.example.publicdata");
        // 옵션을 반영해 쓴 IR은 검수 뒤 빌드에서 옵션 없이 다시 생성한다
        int writtenIrExit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", fromDocument.resolve("ir").toString(), "--output", fromWrittenIr.toString());

        assertEquals(0, documentExit);
        assertEquals(0, plainIrExit);
        assertEquals(0, writtenIrExit);
        Map<String, String> generated = generatedFiles(fromDocument);
        assertFalse(generated.isEmpty());
        assertEquals(generatedFiles(fromPlainIr), generated);
        assertEquals(generated, generatedFiles(fromWrittenIr));
    }

    @Test
    void 덮어쓰기_옵션을_줘도_입력한_IR_JSON_파일은_고치지_않는다() throws Exception {
        // 빌드가 커밋된 IR을 바꾸면 안 된다. 입력 IR에는 생성할 때만 적용한다
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path inputIr = Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(docPath("msrstn-info.docx"), inputDir.resolve("msrstn-info.docx"));
        byte[] original = Files.readAllBytes(inputIr);
        Path fileOutput = tempDir.resolve("file-output");
        Path directoryOutput = tempDir.resolve("directory-output");

        int fileExit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputIr.toString(), "--output", fileOutput.toString(),
                "--success-code", "99", "--format", "json", "--package", "com.example.publicdata");
        int directoryExit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", directoryOutput.toString(),
                "--success-code", "99", "--format", "json", "--package", "com.example.publicdata");

        assertEquals(0, fileExit);
        assertEquals(0, directoryExit);
        assertArrayEquals(original, Files.readAllBytes(inputIr));
        for (Path output : List.of(fileOutput, directoryOutput)) {
            String client = Files.readString(output.resolve(
                    "com/example/publicdata/rtmsdatasvcapttradedev/RTMSDataSvcAptTradeDevClient.java"));
            assertTrue(client.contains("SUCCESS_CODE = \"99\""));
            assertFalse(Files.exists(output.resolve("ir/RTMSDataSvcAptTradeDev.json")),
                    "입력 IR은 출력 디렉터리에 다시 쓰지 않는다");
        }
    }

    @Test
    void apiId가_겹치면_앞서_쓴_IR을_덮어쓰기_전에_경고한다() throws Exception {
        // 두 문서 모두 MsrstnList를 만든다. 디렉터리는 이름순으로 변환하므로 DOCX의 IR을 HWPX가 덮어쓴다
        Path inputDir = Files.createDirectories(tempDir.resolve("dup-docs"));
        Files.copy(docPath("msrstn-info.docx"), inputDir.resolve("msrstn-info.docx"));
        Files.copy(docPath("msrstn-info.hwpx"), inputDir.resolve("msrstn-info.hwpx"));
        Path output = tempDir.resolve("dup-output");
        Path irFile = output.resolve("ir/MsrstnList.json");
        StringWriter err = new StringWriter();
        List<String> sourceAtWarning = new ArrayList<>();
        CommandLine cli = new CommandLine(new H2SpecCli());
        // 경고가 출력되는 순간 IR 파일이 어느 문서의 것인지 기록한다
        cli.setErr(new PrintWriter(new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                String text = new String(buffer, offset, length);
                if (text.contains("apiId가 겹쳐")) {
                    sourceAtWarning.add(readIr(irFile).at("/metadata/sourceFile").asText());
                }
                err.write(text);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }));

        int exit = cli.execute("convert", "--input", inputDir.toString(), "--output", output.toString());

        assertEquals(0, exit);
        assertTrue(err.toString().contains(
                "apiId가 겹쳐 앞선 산출물을 덮어씁니다: MsrstnList (msrstn-info.docx → msrstn-info.hwpx)"), err.toString());
        assertEquals(List.of("msrstn-info.docx"), sourceAtWarning, "앞 문서의 IR이 남아 있을 때 경고해야 한다");
        assertEquals("msrstn-info.hwpx", readIr(irFile).at("/metadata/sourceFile").asText());
    }

    private Path irPath() throws Exception {
        return Path.of(getClass().getResource("/ir/schema-example.json").toURI());
    }

    private Path docPath(String fileName) throws Exception {
        return Path.of(getClass().getResource("/docs/" + fileName).toURI());
    }

    private ObjectNode readIr(Path file) throws IOException {
        return (ObjectNode) new ObjectMapper().readTree(file.toFile());
    }

    /** 옵션만 해석한 convert 명령. 합성한 오퍼레이션을 convertDocument에 바로 넘기므로 입력 파일은 읽지 않는다 */
    private ConvertCommand parsedCommand(StringWriter err) {
        ConvertCommand command = new ConvertCommand();
        CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(err));
        cli.parseArgs("--input", "synthetic.hwp", "--output", tempDir.toString());
        return command;
    }

    /** 파서가 돌려주는 것과 같은 꼴로, schema-example.json에서 apiId와 패키지만 바꾼 오퍼레이션 */
    private ParsedApi syntheticApi(String apiId) throws Exception {
        ObjectNode ir = readIr(irPath());
        ir.withObject("/api").put("apiId", apiId);
        ir.withObject("/generatorHints").put("targetPackage", "kr.go.h2spec.client." + apiId.toLowerCase(Locale.ROOT));
        return new ParsedApi(apiId, ir);
    }

    /** generator가 받지 못하는 오퍼레이션. 필수 필드인 baseUrl이 없다 */
    private ParsedApi brokenApi(String apiId) throws Exception {
        ParsedApi api = syntheticApi(apiId);
        api.ir().withObject("/api").remove("baseUrl");
        return api;
    }

    /** IR을 뺀 생성 산출물을 출력 디렉터리 기준 경로별 내용으로 모은다 */
    private Map<String, String> generatedFiles(Path outputDir) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(outputDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = outputDir.relativize(file);
                if (!relative.startsWith("ir")) {
                    contents.put(relative.toString(), Files.readString(file));
                }
            }
        }
        return contents;
    }
}
