package br.com.ia369.atendente.adapter.in.webhook;

import br.com.ia369.atendente.config.TelnyxProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

    @Test
    void shouldVerifyValidRaw32ByteEd25519KeyAndRejectTamperedOrExpiredPayloads() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        byte[] x509 = kp.getPublic().getEncoded();
        byte[] raw32 = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
        String publicKeyB64 = Base64.getEncoder().encodeToString(raw32);

        TelnyxProperties props = new TelnyxProperties(
                "KEY", publicKeyB64, "https://api.telnyx.com/v2",
                "conn", "acc", "ast", "+551151189636", "https://hooks.test",
                true, 300, 45, 600, "Enable"
        );
        SignatureVerifier verifier = new SignatureVerifier(props);

        byte[] body = "CallSid=v3%3Aabc&CallStatus=completed".getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = sign(kp, ts, body);

        assertThat(verifier.verify(body, sig, ts)).isTrue();
        assertThat(verifier.verify("CallSid=v3%3Atampered".getBytes(StandardCharsets.UTF_8), sig, ts)).isFalse();

        String expiredTs = String.valueOf(Instant.now().getEpochSecond() - 600);
        String expiredSig = sign(kp, expiredTs, body);
        assertThat(verifier.verify(body, expiredSig, expiredTs)).isFalse();

        assertThat(verifier.verify(body, "invalid-b64", ts)).isFalse();
        assertThat(verifier.verify(body, null, ts)).isFalse();
    }

    static String sign(KeyPair kp, String timestamp, byte[] body) throws Exception {
        byte[] prefix = (timestamp + "|").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(body, 0, payload, prefix.length, body.length);

        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(payload);
        return Base64.getEncoder().encodeToString(s.sign());
    }
}

