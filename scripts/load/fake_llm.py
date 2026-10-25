"""OpenAI 호환 가짜 LLM 서버 (#182). 부하 시험에서 실제 모델 대신 쓴다.

POST /v1/chat/completions 에 정해진 지연 뒤 자연어 명령 응답(LIST_REPOSITORIES)을 돌려준다.
  python3 scripts/load/fake_llm.py --port 18090 --delay 3            # 정상, 3초
  python3 scripts/load/fake_llm.py --port 18090 --mode hang          # 응답하지 않음(연결은 받음)
  python3 scripts/load/fake_llm.py --port 18090 --mode error         # 500
GET /stats 는 받은 요청 수와 동시 처리 최대치를 돌려준다. 127.0.0.1에만 연다.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY = {"intent": "LIST_REPOSITORIES", "args": {}, "confidence": 0.95, "message": "저장소 목록을 조회합니다."}
# #174 운영 확인의 자연어 명령 1건과 같은 토큰 수
USAGE = {"prompt_tokens": 1290, "completion_tokens": 46, "total_tokens": 1336,
         "prompt_tokens_details": {"cached_tokens": 0}, "completion_tokens_details": {"reasoning_tokens": 0}}

lock = threading.Lock()
stats = {"requests": 0, "in_flight": 0, "max_in_flight": 0}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/stats":
            with lock:
                self._send(200, dict(stats))
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with lock:
            stats["requests"] += 1
            stats["in_flight"] += 1
            stats["max_in_flight"] = max(stats["max_in_flight"], stats["in_flight"])
        try:
            if ARGS.mode == "hang":
                time.sleep(24 * 3600)
            time.sleep(ARGS.delay)
            if ARGS.mode == "error":
                self._send(500, {"error": {"message": "fake error", "type": "server_error"}})
                return
            self._send(200, {
                "id": "chatcmpl-fake", "object": "chat.completion", "created": int(time.time()), "model": ARGS.model,
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": json.dumps(REPLY, ensure_ascii=False)}}],
                "usage": USAGE,
            })
        finally:
            with lock:
                stats["in_flight"] -= 1


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=18090)
    p.add_argument("--delay", type=float, default=3.0)
    p.add_argument("--mode", choices=["ok", "hang", "error"], default="ok")
    # 가격표에 있는 이름이어야 비용이 계산된다(S4)
    p.add_argument("--model", default="gpt-6-luna")
    ARGS = p.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    server.daemon_threads = True
    print(f"fake llm on 127.0.0.1:{ARGS.port} mode={ARGS.mode} delay={ARGS.delay}s model={ARGS.model}", flush=True)
    server.serve_forever()
