package com.example.ex1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 코드지니어스 와의 S2S 인증(HMAC-SHA256) 공용 유틸 + 공유 시크릿.
 */
public final class AivleSecurity {

    /** 키볼트로 전환 해서 하십셩*/
    public static final String SECRET = "FzNUchoys1wFEvrNsa72PeL9DLTLmK9Qh0yG5ky3yTg=";

    /** 타임스탬프 허용 오차(ms). 코드지니어스와 맞춤. */
    public static final long CLOCK_SKEW_MS = 300_000L;

    private AivleSecurity() {
    }

    public static String hmacHex(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
