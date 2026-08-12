package kr.go.h2spec.generator.emit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaNamesTest {

    @Test
    void camel은_스네이크케이스를_변환한다() {
        assertEquals("lawdCd", JavaNames.camel("LAWD_CD"));
        assertEquals("dealYmd", JavaNames.camel("DEAL_YMD"));
    }

    @Test
    void camel은_구분자없는_이름을_보존한다() {
        assertEquals("numOfRows", JavaNames.camel("numOfRows"));
        assertEquals("serviceKey", JavaNames.camel("serviceKey"));
        assertEquals("resultCode", JavaNames.camel("resultCode"));
    }

    @Test
    void pascal은_첫글자만_올린다() {
        assertEquals("Items", JavaNames.pascal("items"));
        assertEquals("Item", JavaNames.pascal("item"));
        assertEquals("RTMSDataSvcAptTradeDev", JavaNames.pascal("RTMSDataSvcAptTradeDev"));
        assertEquals("LawdCd", JavaNames.pascal("LAWD_CD"));
    }
}
