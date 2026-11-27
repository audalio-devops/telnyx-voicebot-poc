package br.com.ia369.atendente.adapter.in.webhook;

import br.com.ia369.atendente.config.TelnyxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

@Component
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);
    private static final byte[] ED25519_X509_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private final TelnyxProperties properties;

    public SignatureVerifier(TelnyxProperties properties) {
        this.properties = properties;
    }

    public boolean verify(byte[] rawBody, String signatureHeader, String timestampHeader) {
        if (!properties.verifyWebhookSignature()) {
            log.warn("Verificação de assinatura Telnyx desabilitada por configuração");
            return true;
        }
        if (properties.publicKey() == null || properties.publicKey().isBlank()) {
            log.error("TELNYX_PUBLIC_KEY não configurada; rejeitando webhook");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()
                || timestampHeader == null || timestampHeader.isBlank()) {
            return false;
        }

        try {
            long ts = Long.parseLong(timestampHeader.trim());
            long now = Instant.now().getEpochSecond();
            long window = properties.webhookReplayWindowSec() > 0 ? properties.webhookReplayWindowSec() : 300L;
            if (Math.abs(now - ts) > window) {
                log.warn("Timestamp do webhook fora da janela anti-replay (ts={}, now={})", ts, now);
                return false;
            }

            byte[] prefix = (timestampHeader.trim() + "|").getBytes(StandardCharsets.UTF_8);
            byte[] signedPayload = new byte[prefix.length + rawBody.length];
            System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
            System.arraycopy(rawBody, 0, signedPayload, prefix.length, rawBody.length);

            PublicKey publicKey = decodeEd25519PublicKey(properties.publicKey().trim());
            byte[] signatureBytes = Base64.getDecoder().decode(signatureHeader.trim());

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signedPayload);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            log.warn("Falha na verificação da assinatura Ed25519: {}", e.getMessage());
            return false;
        }
    }

    public static PublicKey decodeEd25519PublicKey(String base64Key) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        byte[] x509Bytes;
        if (raw.length == 32) {
            x509Bytes = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, x509Bytes, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, x509Bytes, ED25519_X509_PREFIX.length, raw.length);
        } else {
            x509Bytes = raw;
        }
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(x509Bytes));
    }
}

