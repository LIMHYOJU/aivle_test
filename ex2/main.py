"""
ex2 (Python / FastAPI) — 코드지니어스 의 /api/aivle 요약 API 를 호출/콜백받는 테스트 (8081).
 
 파이썬 안하시니 일단 혹시 몰라 실행 방법 적습니다 (파이썬 설치하시고 하셔야 합니당)
실행:
  pip install -r requirements.txt
  python main.py                 
  
동작:
  1) FastAPI 로 8081/aivle 콜백 수신 라우트를 띄우고(항상 대기),
  2) 서버 기동 직후 백그라운드에서 lecture.mp4 → ffmpeg 음원추출,
  3) 코드지니어스(POST /api/aivle/summarize)로 raw 스트리밍 전송 → jobId 수신,
  4) GET /api/aivle/jobs/{jobId} 를 2초 간격 폴링하며 진행상황 로그 출력.

ffmpeg 는 "라이브러리"가 아니라 OS 에 설치된 ffmpeg 실행파일(PATH)을 subprocess 로 호출한다.
"""

import hashlib
import hmac
import json
import logging
import os
import subprocess
import tempfile
import threading
import time
from contextlib import asynccontextmanager
from urllib.parse import quote

import requests
import uvicorn
from fastapi import FastAPI, Request, Response

# ===== 설정 =====
WEB_AI_BASE_URL = "https://dev-ai.educodegenius.com"  # 요약 서버(코드지니어스)
FFMPEG = "ffmpeg"  # PATH 에 있으면 그대로, 없으면 절대경로
PORT = 8081

# ===== S2S 인증(HMAC) — 코드지니어스 의 AIVLE_HMAC_SECRET 과 반드시 동일 =====
# 샘플이라 하드코딩. 대용량이라 바디는 서명하지 않고 메타데이터만 서명(바디 무결성은 TLS 담당).
#   업로드 canonical: POST\n/api/aivle/summarize\n{pk}\n{timestamp}
#   콜백   canonical: {timestamp}\n{JSON 본문}
HMAC_SECRET = "FzNUchoys1wFEvrNsa72PeL9DLTLmK9Qh0yG5ky3yTg=" #키볼트로 전환 해서 하십셩
CLOCK_SKEW_MS = 300_000                             # 타임스탬프 허용 오차(ms). 코드지니어스 clockSkewSec(300) 과 맞춤


def hmac_hex(canonical: str) -> str:
    """canonical 문자열의 HMAC-SHA256 을 소문자 hex 로 반환."""
    return hmac.new(HMAC_SECRET.encode("utf-8"),
                    canonical.encode("utf-8"), hashlib.sha256).hexdigest()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("ex2")


# ===== FastAPI 앱 + 콜백 라우트 =====
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 폴링처리 안할거면 스레드로 돌릴필요없습니다
    threading.Thread(target=client_flow, daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)


@app.post("/aivle")
async def aivle_callback(request: Request):
    """코드지니어스 가 요약 완료 후 결과(pk + 요약)를 밀어 넣는 콜백. 받기만 하고 끝(fire-and-forget).

    HMAC 검증: 원문 바이트 그대로 받아 canonical = {X-Timestamp}\n{원문 JSON} 로 검증.
    서명 헤더가 없으면(코드지니어스 시크릿 미설정) 검증을 생략한다.
    """
    body = await request.body()
    ts = request.headers.get("X-Timestamp")
    signature = request.headers.get("X-Signature")

    if ts and signature:
        try:
            ts_val = int(ts)
        except ValueError:
            log.warning("[콜백] X-Timestamp 형식 오류 — 거절")
            return Response(status_code=401)
        if abs(int(time.time() * 1000) - ts_val) > CLOCK_SKEW_MS:
            log.warning("[콜백] 타임스탬프 만료 — 거절")
            return Response(status_code=401)
        expected = hmac_hex(ts + "\n" + body.decode("utf-8"))
        if not hmac.compare_digest(expected, signature.strip()):
            log.warning("[콜백] 서명 불일치 — 거절")
            return Response(status_code=401)
        log.info("[콜백] 서명 검증 통과")
    else:
        log.warning("[콜백] 서명 헤더 없음 — 검증 생략(무방비)")

    data = json.loads(body) if body else {}
    log.info("========== [콜백 수신] pk=%s status=%s ==========",
             data.get("pk"), data.get("status"))
    if data.get("summary"):
        log.info("[콜백] 요약:\n%s", data.get("summary"))
    if data.get("error"):
        log.info("[콜백] 오류: %s", data.get("error"))
    # 응답 바디 없이 빈 200. 코드지니어스 는 결과를 쏘고 끝내므로 ack 내용을 필요로 하지 않는다.
    return Response(status_code=200)


# ===== 1) ffmpeg 음원 추출 =====
def extract_audio(src: str) -> str:
    fd, out = tempfile.mkstemp(prefix="ex2-audio-", suffix=".wav")
    os.close(fd)
    log.info("[1] ffmpeg 음원추출 시작 %s -> %s", src, out)
    cmd = [FFMPEG, "-y", "-i", src,
           "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", out]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        log.error("[1] ffmpeg 실패(exit=%d):\n%s", r.returncode,
                  r.stdout.decode("utf-8", "ignore")[-2000:])
        raise RuntimeError("ffmpeg 음원추출 실패")
    log.info("[1] ffmpeg 음원추출 완료 size=%d bytes", os.path.getsize(out))
    return out


# ===== 2) 코드지니어스 로 전송 (raw 스트리밍) → jobId =====
def send_audio(wav: str, pk: str) -> str:
    url = WEB_AI_BASE_URL + "/api/aivle/summarize"
    # HMAC 서명: 바디는 서명하지 않고 메타데이터만 서명.
    # canonical: POST\n/api/aivle/summarize\n{pk}\n{timestamp}
    ts = str(int(time.time() * 1000))
    signature = hmac_hex("POST\n/api/aivle/summarize\n" + pk + "\n" + ts)
    headers = {
        "Content-Type": "application/octet-stream",
        "X-Pk": pk,
        "X-Filename": quote(os.path.basename(wav)),
        "X-Timestamp": ts,
        "X-Signature": signature,
    }
    # data=파일객체 → requests 가 파일 크기만큼 스트리밍 전송(메모리 미적재).
    with open(wav, "rb") as f:
        resp = requests.post(url, data=f, headers=headers, timeout=600)
    resp.raise_for_status()
    body = resp.json()
    if not body.get("result"):
        raise RuntimeError("요약 요청 실패: " + str(body.get("message")))
    job_id = body["data"]["jobId"]
    log.info("[2] 요약 요청 전송 완료 jobId=%s pk=%s", job_id, pk)
    return job_id


# ===== 3) 폴링 =====
def poll_until_done(job_id: str):
    url = WEB_AI_BASE_URL + "/api/aivle/jobs/" + job_id
    for _ in range(900):   # 최대 30분(2s*900)
        try:
            resp = requests.get(url, timeout=30)
            body = resp.json()
        except Exception as e:
            log.warning("[3] 폴링 일시 실패(재시도): %s", e)
            _sleep(2)
            continue
        data = body.get("data", {})
        status = data.get("status")
        progress = int(round((data.get("progress") or 0) * 100))
        message = data.get("message")
        log.info("[3] 폴링 status=%s progress=%d%% msg=%s", status, progress, message)
        if status == "done":
            log.info("[3] ✔ 요약 완료! (폴링 결과)\n---- summary ----\n%s\n----------------",
                     data.get("summary"))
            return
        if status == "error":
            log.error("[3] ✖ 요약 실패: %s", data.get("error"))
            return
        _sleep(2)
    log.warning("[3] 폴링 타임아웃(30분) — 중단")


# ===== 클라이언트 전체 흐름(백그라운드 스레드에서 실행) =====
def client_flow():
    
    # DB조회해서 해당 vod pk와 해당 저장 경로 유니크든 매핑하실거
    VIDEO_FILE = r"C:\Users\dlagy\Downloads\lecture.mp4"
    PK = "ex2-0001"  
    if not os.path.exists(VIDEO_FILE):
        log.error("영상 파일을 찾을 수 없습니다: %s", VIDEO_FILE)
        return
    try:
        wav = extract_audio(VIDEO_FILE)
    except Exception as e:
        log.error("[1] 추출 단계 실패: %s", e)
        return
    try:
        job_id = send_audio(wav, PK)
        poll_until_done(job_id)
    except Exception as e:
        log.error("[ex2] 테스트 흐름 실패: %s", e)
    finally:
        try:
            os.remove(wav)
        except OSError:
            pass


def _sleep(sec: float):
    import time
    time.sleep(sec)


if __name__ == "__main__":
    # uvicorn 자체 접근로그는 끄고(warning) 우리 로그만 보이게.
    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="warning")
