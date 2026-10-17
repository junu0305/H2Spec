package kr.go.h2spec.client.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceKeyMaskerTest {

    /** 공공데이터포털 키처럼 인코딩하면 꼴이 바뀌는 글자와, 질의(+)와 경로(%20)에서 다르게 쓰는 공백을 넣는다 */
    private static final String KEY = "K3y+/= x";

    @Test
    void 키를_주면_원래_값과_질의용_경로용_인코딩을_모두_가린다() {
        String text = "http://api.example/svc/K3y%2B%2F%3D%20x?apiKey=K3y%2B%2F%3D+x&pageNo=1 원문: K3y+/= x";

        assertEquals("http://api.example/svc/****?apiKey=****&pageNo=1 원문: ****", ServiceKeyMasker.mask(text, KEY));
    }

    @Test
    void 키를_주어도_serviceKey_값은_이름으로_가린다() {
        assertEquals("http://api.example/test?serviceKey=****&apiKey=****",
                ServiceKeyMasker.mask("http://api.example/test?serviceKey=OTHER&apiKey=K3y%2B%2F%3D+x", KEY));
    }

    @Test
    void 키가_없으면_serviceKey_값만_가린다() {
        String text = "http://api.example/test?serviceKey=SECRET&apiKey=K3y";

        assertEquals(ServiceKeyMasker.mask(text), ServiceKeyMasker.mask(text, null));
        assertEquals(ServiceKeyMasker.mask(text), ServiceKeyMasker.mask(text, ""));
        assertNull(ServiceKeyMasker.mask(null, KEY));
    }
}
