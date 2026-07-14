package com.example.ex1;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 코드지니어스 가 요약 완료 후 결과 콜백 수신 엔드포인트.
 *
 */
@RestController
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);
    private final ObjectMapper om = new ObjectMapper();

    @PostMapping("/aivle")
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Timestamp", required = false) String ts,
            @RequestHeader(value = "X-Signature", required = false) String signature) {

        byte[] body = rawBody == null ? new byte[0] : rawBody;

        // 서명 헤더가 오면 검증. (코드지니어스 시크릿 미설정 시엔 헤더가 없어 검증 생략)
        if (ts != null && signature != null) {
            long tsVal;
            try {
                tsVal = Long.parseLong(ts.trim());
            } catch (NumberFormatException e) {
                log.warn("[ex1][콜백] X-Timestamp 형식 오류 — 거절");
                return ResponseEntity.status(401).build();
            }
            if (Math.abs(System.currentTimeMillis() - tsVal) > AivleSecurity.CLOCK_SKEW_MS) {
                log.warn("[ex1][콜백] 타임스탬프 만료 — 거절");
                return ResponseEntity.status(401).build();
            }
            String expected = AivleSecurity.hmacHex(ts + "\n" + new String(body, StandardCharsets.UTF_8));
            if (!AivleSecurity.constantTimeEquals(expected, signature.trim())) {
                log.warn("[ex1][콜백] 서명 불일치 — 거절");
                return ResponseEntity.status(401).build();
            }
            log.info("[ex1][콜백] 서명 검증 통과");
        } else {
            log.warn("[ex1][콜백] 서명 헤더 없음 — 검증 생략(무방비 로컬테스트 한겁니다 여기서 걸러줘야해요)");
        }

        try {
            Map<String, Object> parsed = body.length == 0
                    ? Map.of()
                    : om.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            String vodMappingKey = String.valueOf(parsed.get("vodMappingKey"));
            Object summaryCn = parsed.get("summaryCn");
            Object downloadUrl = parsed.get("downloadUrl");

            log.info("========== [ex1][콜백 수신] vodMappingKey={} ==========", vodMappingKey);
            if (summaryCn != null) {
                log.info("[ex1][콜백] 요약(summaryCn):\n{}", summaryCn);
            }
            if (downloadUrl != null) {
                log.info("[ex1][콜백] 요약 txt 다운로드(SAS): {}", downloadUrl);
            }
        } catch (Exception e) {
            log.warn("[ex1][콜백] 본문 파싱 실패: {}", e.getMessage());
        }
        // 응답 바디 없음. .응답했다는거만..
        return ResponseEntity.ok().build();
    }
}
