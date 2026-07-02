package com.example.ex1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SummaryClientRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SummaryClientRunner.class);

    @Value("${app.codegenius-base-url}")// 코드지니어스 도메인으로 개발,운영으로 바꾸셔야합니다
    private String BaseUrl;
    @Value("${app.ffmpeg}")//설치 위치에따라서 제 로컬같은경우는 path등록해서 ffmpeg로 바로 한 것 뿐 설치하시고 그위치로 지정하시면 됩니다
    private String ffmpeg;


    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) {
        /**
         * DB조회해서 해당 vod pk와 해당 저장 경로 유니크든 매핑하실거
         */
        String videoFile = "C:/Users/dlagy/Downloads/lecture.mp4";
        String pk = "ex1-0001";
        flow(pk,videoFile);
    }

    private void flow(String pk,String videoFile) {
        try {
            Thread.sleep(1000);   // 서버 안정화 잠깐 대기
            Path mp4 = Paths.get(videoFile);
            if (!Files.exists(mp4)) {
                log.error("[ex1] 영상 파일을 찾을 수 없습니다: {}", mp4.toAbsolutePath());
                return;
            }

            // 1) ffmpeg 음원 추출
            Path wav = Files.createTempFile("ex1-audio-", ".wav");
            log.info("[ex1] (1) ffmpeg 음원추출 시작 {} -> {}", mp4, wav);
            extractAudio(mp4, wav);
            log.info("[ex1] (1) ffmpeg 음원추출 완료 size={} bytes", Files.size(wav));

            // 2) web-ai 전송 → jobId
            String jobId = sendAudio(wav, pk);
            log.info("[ex1] (2) 요약 요청 전송 완료 jobId={} pk={}", jobId, pk);

            // 3) 폴링
            pollUntilDone(jobId);

            Files.deleteIfExists(wav);
        } catch (Exception e) {
            log.error("[ex1] 테스트 흐름 실패", e);
        }
    }

    /** ffmpeg 로 WAV 파일 추출  */
    private void extractAudio(Path in, Path out) throws Exception {
        List<String> cmd = List.of(
                ffmpeg, "-y", "-i", in.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                /** ffmpeg 진행 로그*/
                log.info("[ex1] ffmpeg={}", line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ffmpeg 음원추출 실패 (exit=" + code + ")");
        }
    }

    /** raw 바디 스트리밍으로 음원 전송(대용량 대비, 메모리 미적재). 응답에서 jobId 추출. */
    private String sendAudio(Path wav, String pk) throws Exception {
        String filename = URLEncoder.encode(wav.getFileName().toString(), StandardCharsets.UTF_8);
        // HMAC 서명: 대용량이라 바디는 서명하지 않고 메타데이터만 서명(바디 무결성은 TLS 담당).
        // canonical: POST\n/api/aivle/summarize\n{pk}\n{timestamp}
        String ts = Long.toString(System.currentTimeMillis());
        String signature = AivleSecurity.hmacHex("POST\n/api/aivle/summarize\n" + pk + "\n" + ts);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BaseUrl + "/api/aivle/summarize"))
                .header("Content-Type", "application/octet-stream")
                .header("X-Filename", filename)
                .header("X-Pk", pk)
                .header("X-Timestamp", ts)
                .header("X-Signature", signature)
                .POST(BodyPublishers.ofFile(wav))    // 파일을 스트리밍(전체를 메모리에 올리지 않음)
                .build();
        HttpResponse<String> res = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 진단: 실제 상태코드/원문 바디를 남긴다(봉투가 아닌 응답이 올 때 원인 파악용).
        log.info("[ex1] (2) 응답 status={} body={}", res.statusCode(), res.body());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "요약 요청 실패: HTTP " + res.statusCode() + " body=" + res.body());
        }
        JsonNode root = om.readTree(res.body());
        if (!root.path("result").asBoolean()) {
            throw new IllegalStateException("요약 요청 실패: " + root.path("message").asText()
                    + " (raw=" + res.body() + ")");
        }
        return root.path("data").path("jobId").asText();
    }

    /** 2초 간격으로 진행상황 폴링. done/error 면 종료.
     * 사실 이건 할필요없습니다 그냥 진행 상황 체크 하시고싶으시면..테스트용으로 ㅇㅇ
     * 안한다면 말씀해주세요 저희쪽에서도 지우겠습니다 사실 콜백만 으로 처리 하는걸로..
     * */
    private void pollUntilDone(String jobId) throws Exception {
        String url = BaseUrl + "/api/aivle/jobs/" + jobId;
        for (int i = 0; i < 900; i++) {   // 최대 30분(2s*900)
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res;
            try {
                res = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("[ex1] (3) 폴링 일시 실패(재시도): {}", e.getMessage());
                Thread.sleep(2000);
                continue;
            }
            JsonNode data = om.readTree(res.body()).path("data");
            String status = data.path("status").asText();
            int progress = (int) Math.round(data.path("progress").asDouble() * 100);
            String message = data.path("message").asText();
            log.info("[ex1] (3) 폴링 status={} progress={}% msg={}", status, progress, message);

            if ("done".equals(status)) {
                log.info("[ex1] (3)  요약 완료 (폴링 결과)\n---- summary ----\n{}\n----------------",
                        data.path("summary").asText());
                return;
            }
            if ("error".equals(status)) {
                log.error("[ex1] (3)  요약 실패: {}", data.path("error").asText());
                return;
            }
            Thread.sleep(2000);
        }
        log.warn("[ex1] (3) 폴링 타임아웃(30분) — 중단");
    }
}
