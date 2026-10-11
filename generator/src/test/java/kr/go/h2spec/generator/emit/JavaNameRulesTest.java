package kr.go.h2spec.generator.emit;

import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaNameRulesTest {

    @Test
    void 원래_이름은_따옴표_역슬래시_제어문자만_거른다() {
        assertTrue(JavaNameRules.isSafeRawName("relevant departments"));
        assertTrue(JavaNameRules.isSafeRawName("측정소"));
        assertTrue(JavaNameRules.isSafeRawName("sido-name"));
        assertTrue(JavaNameRules.isSafeRawName("a$b"));
        assertTrue(JavaNameRules.isSafeRawName("item.name"), "점은 Java 이름 검사가 거른다");

        assertFalse(JavaNameRules.isSafeRawName("page\"No"));
        assertFalse(JavaNameRules.isSafeRawName("page\\No"));
        assertFalse(JavaNameRules.isSafeRawName("page\nNo"));
        assertFalse(JavaNameRules.isSafeRawName("page\0No"));
        assertFalse(JavaNameRules.isSafeRawName(null));
    }

    @Test
    void 공백_한글_하이픈_달러가_든_이름도_Java_이름이_식별자면_받는다() {
        // 지금 잘 생성되는 이름이다. 원래 이름이 식별자 꼴인지로 거르면 이 이름들을 거부하게 된다
        assertEquals("relevantDepartments", JavaNameRules.javaName("relevant departments"));
        assertTrue(JavaNameRules.hasValidJavaName("relevant departments"));
        assertTrue(JavaNameRules.hasValidJavaName("측정소"));
        assertTrue(JavaNameRules.hasValidJavaName("sido-name"));
        assertTrue(JavaNameRules.hasValidJavaName("a$b"));
        assertTrue(JavaNameRules.hasValidJavaName("LAWD_CD"));
    }

    @Test
    void 점이_든_이름은_isName이_받아도_식별자가_아니다() {
        // SourceVersion.isName은 점으로 이은 이름을 받아서 식별자 검사에 쓰면 item.name이 통과한다
        assertTrue(SourceVersion.isName("item.name"));
        assertFalse(JavaNameRules.hasValidJavaName("item.name"));
    }

    @Test
    void Java_이름이_예약어이거나_숫자로_시작하거나_비면_거부한다() {
        assertFalse(JavaNameRules.hasValidJavaName("class"));
        assertFalse(JavaNameRules.hasValidJavaName("Class"), "첫 글자를 내리면 예약어 class가 된다");
        assertFalse(JavaNameRules.hasValidJavaName("true"));
        assertFalse(JavaNameRules.hasValidJavaName("_"));
        assertFalse(JavaNameRules.hasValidJavaName("1st"));
        assertFalse(JavaNameRules.hasValidJavaName(""));
        assertFalse(JavaNameRules.hasValidJavaName(null));
    }

    @Test
    void 구분자로만_된_이름은_빈_Java_이름이_되어_거부한다() {
        // 나눈 결과가 비는 이름이다. 여기서 예외가 나면 검사가 거부하는 대신 생성기가 멈춘다
        assertEquals("", JavaNameRules.javaName("-"));
        assertEquals("", JavaNameRules.javaName("__"));
        assertFalse(JavaNameRules.hasValidJavaName("-"));
    }

    @Test
    void 식별자_검사는_메서드명처럼_생성기가_만든_이름에도_쓴다() {
        assertTrue(JavaNameRules.isValidIdentifier("itemsItemidDetail"));
        assertTrue(JavaNameRules.isValidIdentifier("getMsrstnList_do"));
        assertFalse(JavaNameRules.isValidIdentifier("getMsrstnList.do"));
        assertFalse(JavaNameRules.isValidIdentifier("7tttadad5ataaba75a57ttdtadb6tb7tYourAppkey"));
        assertFalse(JavaNameRules.isValidIdentifier("new"));
        assertFalse(JavaNameRules.isValidIdentifier(null));
    }

    @Test
    void javac가_빼고_읽는_서식_문자는_빼고_판단한다() {
        // soft hyphen은 식별자 안에 들어가도 javac가 빼고 읽는다. cl과 ass 사이에 넣으면 예약어 class가 된다
        String softHyphen = "\u00AD";
        assertFalse(JavaNameRules.hasValidJavaName("cl" + softHyphen + "ass"));
        assertEquals(Map.of("pageNo", List.of("pageNo", "page" + softHyphen + "No")),
                JavaNameRules.javaNameCollisions(List.of("pageNo", "page" + softHyphen + "No")));
    }

    @Test
    void Java_이름이_겹치는_원래_이름을_입력_순서대로_묶는다() {
        Map<String, List<String>> collisions = JavaNameRules.javaNameCollisions(
                List.of("station_name", "pageNo", "stationName", "STATION-NAME"));

        assertEquals(Map.of("stationName", List.of("station_name", "stationName", "STATION-NAME")), collisions);
    }

    @Test
    void 같은_이름이_두_번_와도_겹침이다() {
        assertEquals(Map.of("pageNo", List.of("pageNo", "pageNo")),
                JavaNameRules.javaNameCollisions(List.of("pageNo", "numOfRows", "pageNo")));
    }

    @Test
    void 겹침이_없으면_빈_맵을_돌려준다() {
        assertTrue(JavaNameRules.javaNameCollisions(List.of("pageNo", "numOfRows", "LAWD_CD")).isEmpty());
    }

    @Test
    void 패키지는_점으로_이은_식별자이고_마디가_예약어가_아니어야_한다() {
        assertTrue(JavaNameRules.isValidPackageName("kr.go.h2spec.client.landmolit"));
        assertTrue(JavaNameRules.isValidPackageName("com.example.publicdata.msrstnlist"));

        // CLI --package가 apiId 소문자를 마디로 붙여서 apiId가 Int·New면 예약어 마디가 생긴다
        assertFalse(JavaNameRules.isValidPackageName("com.example.int"));
        assertFalse(JavaNameRules.isValidPackageName("com.example.new"));
        assertFalse(JavaNameRules.isValidPackageName("com..example"));
        assertFalse(JavaNameRules.isValidPackageName("com/example"));
        assertFalse(JavaNameRules.isValidPackageName("../escaped"));
        assertFalse(JavaNameRules.isValidPackageName(""));
        assertFalse(JavaNameRules.isValidPackageName(null));
    }

    @Test
    void apiId는_toApiId가_만드는_꼴만_받는다() {
        assertTrue(JavaNameRules.isValidApiId("RTMSDataSvcAptTradeDev"));
        assertTrue(JavaNameRules.isValidApiId("Api7tttaDaD5ataaba75a57ttDtaDb6tb7t"));
        assertTrue(JavaNameRules.isValidApiId("MsrstnList_do"));

        assertFalse(JavaNameRules.isValidApiId("../../escaped"));
        assertFalse(JavaNameRules.isValidApiId("a/b"));
        assertFalse(JavaNameRules.isValidApiId("7tttaDaD5"));
        assertFalse(JavaNameRules.isValidApiId("_Api"));
        assertFalse(JavaNameRules.isValidApiId("측정소"));
        assertFalse(JavaNameRules.isValidApiId(""));
        assertFalse(JavaNameRules.isValidApiId(null));
    }
}
