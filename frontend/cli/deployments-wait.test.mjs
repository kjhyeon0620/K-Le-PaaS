import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import test from "node:test";

for (const status of ["UNKNOWN", "FAILED", "CANCELED"]) {
  test(`${status}은 재조회 없이 exit 3과 판단 이유를 반환한다`, async (t) => {
    let requests = 0;
    const reason = "적용 근거가 없어 결과를 판단할 수 없습니다.";
    const server = createServer((request, response) => {
      requests++;
      assert.equal(request.url, "/api/v1/deployments/57/status");
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ status: "success", data: { status, fail_reason: reason } }));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    t.after(() => new Promise((resolve) => server.close(resolve)));

    const result = await new Promise((resolve) => {
      execFile(process.execPath, [fileURLToPath(new URL("./index.mjs", import.meta.url)),
        "deployments", "wait", "57", "--json", "--timeout", "2", "--interval", "1"], {
        env: { ...process.env, KLEPAAS_BASE_URL: `http://127.0.0.1:${server.address().port}`, KLEPAAS_TOKEN: "local-test-token" },
        timeout: 5000,
      }, (error, stdout, stderr) => resolve({ error, stdout, stderr }));
    });

    assert.equal(result.error?.code, 3, result.stderr);
    const output = JSON.parse(result.stdout);
    assert.equal(output.final_status, status);
    assert.equal(output.fail_reason, reason);
    assert.equal(output.timeline.length, 1);
    assert.equal(requests, 1);
  });
}

function runCli(server, args) {
  return new Promise((resolve) => {
    execFile(process.execPath, [fileURLToPath(new URL("./index.mjs", import.meta.url)), ...args], {
      env: { ...process.env, KLEPAAS_BASE_URL: `http://127.0.0.1:${server.address().port}`, KLEPAAS_TOKEN: "local-test-token" },
      timeout: 5000,
    }, (error, stdout, stderr) => resolve({ error, stdout, stderr }));
  });
}

test("ask는 승인 대상을 보여 주고, 설정이 바뀐 confirm 409는 기존 4xx 규칙대로 exit 1과 이유를 낸다 (#56)", async (t) => {
  const conflict = "승인 후 배포 설정이 바뀌었거나 승인 대상을 확인할 수 없습니다. 명령을 다시 보내 승인하세요";
  const server = createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (request.url === "/api/v1/nlp/command") {
      response.end(JSON.stringify({ status: "success", data: {
        command_log_id: 7, intent: "DEPLOY", risk_level: "HIGH", requires_confirmation: true, message: "배포합니다",
        approval_target: { repository_id: 2, repository: "owner/app", branch: "main", commit: "abcdef1",
          build_strategy: "GITHUB_ACTIONS_GHCR", image: "ghcr.io/owner/app:sha-{commitHash}", min_replicas: 1,
          max_replicas: 1, container_port: 8080, env_names: ["SAMPLE_GREETING"], config_fingerprint: "1a2b3c4d" },
      } }));
    } else {
      response.statusCode = 409;
      response.end(JSON.stringify({ status: "error", code: "AI_006", message: conflict }));
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const ask = await runCli(server, ["ask", "배포해줘"]);
  assert.equal(ask.error, null, ask.stderr);
  assert.match(ask.stdout, /owner\/app \(#2\)/);
  assert.match(ask.stdout, /SAMPLE_GREETING/);
  assert.match(ask.stdout, /1a2b3c4d/);

  const confirm = await runCli(server, ["confirm", "7", "--yes"]);
  assert.equal(confirm.error?.code, 1);
  assert.match(confirm.stderr, /명령을 다시 보내 승인하세요/);
});
