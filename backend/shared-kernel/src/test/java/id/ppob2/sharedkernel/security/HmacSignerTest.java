package id.ppob2.sharedkernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacSignerTest {

    @Test
    void sameInputsProduceSameSignature() {
        String bodyHash = HmacSigner.sha256Hex("{}");
        String stringToSign = HmacSigner.buildStringToSign("GET", "/api/v1/config/supported-amounts", "1700000000000", "nonce-1", bodyHash);

        String signature = HmacSigner.sign("client-secret", stringToSign);

        assertThat(HmacSigner.matches("client-secret", stringToSign, signature)).isTrue();
        assertThat(HmacSigner.matches("wrong-secret", stringToSign, signature)).isFalse();
    }

    @Test
    void differentBodyProducesDifferentHash() {
        assertThat(HmacSigner.sha256Hex("{}")).isNotEqualTo(HmacSigner.sha256Hex("{\"a\":1}"));
    }
}
