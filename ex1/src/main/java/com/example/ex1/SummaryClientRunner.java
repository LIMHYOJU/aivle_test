package com.example.ex1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 기능1 — 코드지니어스로 VOD 매핑키+이름을 등록한다(JSON).
 */
@Component
public class SummaryClientRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SummaryClientRunner.class);

    @Value("${app.codegenius-base-url}")   // 코드지니어스 도메인(개발/운영으로 교체)
    private String BaseUrl;
    @Value("${app.vod-mapping-key}")
    private String vodMappingKey;
    @Value("${app.vod-name}")
    private String vodName;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) {
        register(vodMappingKey, vodName);
    }

    private void register(String vodMappingKey, String vodName) {
        try {
            Thread.sleep(1000);   // 서버 안정화 잠깐 대기

            ObjectNode node = om.createObjectNode();
            node.put("vodMappingKey", vodMappingKey);
            node.put("vodName", vodName);

            String body = om.writeValueAsString(node);

            // HMAC 서명: canonical {timestamp}\n{본문} (바디 포함 — 코드지니어스 검증/콜백 서명과 대칭).
            String ts = Long.toString(System.currentTimeMillis());
            String signature = AivleSecurity.hmacHex(ts + "\n" + body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BaseUrl + "/api/aivle/summarize"))
                    .header("Content-Type", "application/json")
                    .header("X-Timestamp", ts)
                    .header("X-Signature", signature)
                    .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 실제 결과는 바디 { code, message } 로 온다.
            //   code 200 = 에이블 먼저(등록/대기), 201 = 업로드 먼저(요약 있음→콜백 올거임 ), "err" = 예외.
            String resBody = res.body();
            String code="";
            String message="";
            try {
                JsonNode json = om.readTree(resBody);
                if (json.hasNonNull("code")) {
                    code = json.get("code").asText();
                }
                if (json.hasNonNull("message")) {
                    message = json.get("message").asText();
                }
            } catch (Exception parseEx) {
                log.warn("[ex1] 응답 바디 파싱 실패 body={}", resBody);
            }

            log.info("[ex1] 기능1 응답 ← vodName={} vodMappingKey={} | HTTP {} | code={} message=\"{}\"",
                    vodName, vodMappingKey, res.statusCode(), code, message);

            if ("200".equals(code)) {
                log.info("[ex1] → 정상(200): 에이블 먼저 등록됨(요약 대기). 코드지니어스 업로드 요약 완료 시 콜백이 온다.");
            } else if ("201".equals(code)) {
                log.info("[ex1] → 정상(201): 요약이 이미 있어 콜백 송신될 예정. /aivle 로 콜백 도착 예정.");
            } else {
                log.error("[ex1] → 실패(code={}): {}", code, message);
            }
        } catch (Exception e) {
            log.error("[ex1] 기능1 등록 흐름 실패", e);
        }
    }
}
