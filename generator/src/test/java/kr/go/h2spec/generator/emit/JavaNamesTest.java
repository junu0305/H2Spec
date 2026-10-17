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

    @Test
    void 식별자에_못_쓰는_글자는_구분자로_보고_잇는다() {
        assertEquals("condCountryNmEq", JavaNames.camel("cond[country_nm::EQ]"));
        assertEquals("CondCountryNmEq", JavaNames.pascal("cond[country_nm::EQ]"));
        assertEquals("itemName", JavaNames.camel("item.name"));
        assertEquals("ItemName", JavaNames.pascal("item.name"));
        assertEquals("", JavaNames.camel("[]"), "남는 글자가 없으면 빈 이름이라 검사가 거부한다");
    }

    @Test
    void 식별자에_못_쓰는_글자가_없는_이름은_예전과_같다() {
        assertEquals("sidoName", JavaNames.camel("sido-name"));
        assertEquals("pageNo", JavaNames.camel("page no"));
        assertEquals("측정소", JavaNames.camel("측정소"));
        assertEquals("a$b", JavaNames.camel("a$b"));
        assertEquals("Items", JavaNames.camel("_items"));
    }

    @Test
    void 예약어와_숫자로_시작하는_이름은_식별자로_고친다() {
        assertEquals("abstract_", JavaNames.camel("abstract"));
        assertEquals("default_", JavaNames.camel("default"));
        assertEquals("_1stPrize", JavaNames.camel("1stPrize"));
        assertEquals("Abstract", JavaNames.pascal("abstract"), "getter는 getAbstract다");
    }
}
