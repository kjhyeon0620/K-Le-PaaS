// CLI 요청 본문 변환 계약 (#109)
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { ApiClient } from "./api.mjs";

test("요청 본문의 필드 이름은 snake_case로 바꾸되 env_vars 맵의 키(env 이름)는 그대로 보낸다", async (t) => {
  let received = null;
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk) => (body += chunk));
    request.on("end", () => {
      received = JSON.parse(body);
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ status: "success", data: {} }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const client = new ApiClient({ baseUrl: `http://127.0.0.1:${server.address().port}` });
  await client.request("/api/v1/repositories/1/config", {
    method: "PUT",
    auth: false,
    body: { minReplicas: 1, envVars: { SAMPLE_GREETING: "hello", fooBar: "x" }, healthProbe: { periodSeconds: 5 } },
  });

  assert.deepEqual(received, {
    min_replicas: 1,
    env_vars: { SAMPLE_GREETING: "hello", fooBar: "x" },
    health_probe: { period_seconds: 5 },
  });
});

test("LLM 사용량 조회는 본인 사용량 경로를 days와 함께 GET으로 부른다 (#176)", async (t) => {
  let seen = null;
  const server = createServer((request, response) => {
    seen = { method: request.method, url: request.url };
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ status: "success", data: { timezone: "Asia/Seoul", days: [] } }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const client = new ApiClient({ baseUrl: `http://127.0.0.1:${server.address().port}` });
  client.accessToken = "t";
  const data = await client.llmUsage(7);

  assert.deepEqual(seen, { method: "GET", url: "/api/v1/llm/usage?days=7" });
  assert.equal(data.timezone, "Asia/Seoul");
});
