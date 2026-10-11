import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

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

test("recover는 계획을 보여 주고 확인한 계획 지문으로만 실행한다 (#101)", async (t) => {
  const posted = [];
  const plan = { target_deployment_id: 3, target_commit: "aaaaaaa1", image: "ghcr.io/owner/app@sha256:111", image_digest: "sha256:111",
    digest_pinned: true, config_changes: [{ field: "min_replicas", before: "2", after: "1", kind: "CHANGED" }],
    env_differences: [], executable: true, blocked_reasons: [], plan_fingerprint: "plan-fp" };
  const server = createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (request.method === "GET" && request.url === "/api/v1/deployments/3/recovery-plan") {
      response.end(JSON.stringify({ status: "success", data: plan }));
      return;
    }
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      posted.push({ url: request.url, body: JSON.parse(body) });
      response.statusCode = 201;
      response.end(JSON.stringify({ status: "success", data: { id: 9, recovered_from_deployment_id: 3 } }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const shown = await runCli(server, ["deployments", "recovery-plan", "3"]);
  assert.equal(shown.error, null, shown.stderr);
  assert.match(shown.stdout, /ghcr\.io\/owner\/app@sha256:111/);
  assert.match(shown.stdout, /min_replicas: 2 → 1/);
  assert.equal(posted.length, 0);

  const recovered = await runCli(server, ["deployments", "recover", "3", "--yes", "--json"]);
  assert.equal(recovered.error, null, recovered.stderr);
  assert.equal(JSON.parse(recovered.stdout).deployment.id, 9);
  assert.deepEqual(posted, [{ url: "/api/v1/deployments/3/recover", body: { plan_fingerprint: "plan-fp" } }]);

  plan.executable = false;
  plan.plan_fingerprint = null;
  plan.blocked_reasons = ["현재 env가 대상 배포와 다릅니다"];
  const blocked = await runCli(server, ["deployments", "recover", "3", "--yes"]);
  assert.equal(blocked.error?.code, 1);
  assert.match(blocked.stderr, /현재 env가 대상 배포와 다릅니다/);
  assert.equal(posted.length, 1);
});

test("diagnose는 진단 결과와 서버가 자른 근거를 보여 준다 (#100)", async (t) => {
  const server = createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    assert.equal(request.method, "POST");
    assert.equal(request.url, "/api/v1/deployments/7/diagnosis");
    response.end(JSON.stringify({ status: "success", data: {
      deployment_id: 7, model: "gemini-2.5-flash", prompt_version: "diagnosis-v1", category: "ENV_CONFIG",
      root_cause: "required_env_missing", abstain: false, summary: "POSTGRES_PASSWORD가 없어 종료를 반복합니다",
      observed_facts: ["직전 컨테이너가 exit 1로 종료"], hypotheses: [], next_checks: ["배포 설정의 env 확인"],
      cited: [{ pointer: "/logs/pods/0/previous_logs/1", excerpt: "You must specify POSTGRES_PASSWORD" }], warnings: [],
      reused: false, diagnosed_at: "2026-10-11T16:00:00" } }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const result = await runCli(server, ["deployments", "diagnose", "7"]);
  assert.equal(result.error, null, result.stderr);
  assert.match(result.stdout, /ENV_CONFIG \/ required_env_missing/);
  assert.match(result.stdout, /\/logs\/pods\/0\/previous_logs\/1\n\s+You must specify POSTGRES_PASSWORD/);
  assert.match(result.stdout, /실험/);
});

test("repositories list는 앱 목록을 보여 준다 (#102)", async (t) => {
  const server = createServer((request, response) => {
    assert.equal(request.url, "/api/v1/repositories");
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ status: "success", data: [
      { id: 7, owner: "owner", repo_name: "app", git_url: "https://example.test/owner/app", cloud_vendor: "ON_PREMISE",
        user_id: 1, created_at: "2026-10-11T00:00:00Z", updated_at: "2026-10-11T00:00:00Z" }] }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const result = await runCli(server, ["repositories", "list"]);
  assert.equal(result.error, null, result.stderr);
  assert.match(result.stdout, /7\s+owner\/app\s+ON_PREMISE/);
});

test("propose-recovery는 승인 대기 명령만 만들고 승인 방법을 안내한다 (#102)", async (t) => {
  const requests = [];
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk) => (body += chunk));
    request.on("end", () => {
      requests.push({ method: request.method, url: request.url, body: JSON.parse(body || "{}") });
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ status: "success", data: {
        command_log_id: 21, intent: "ROLLBACK", message: "복구 제안을 만들었습니다.", result: null, risk_level: "HIGH",
        requires_confirmation: true, session_id: null, approval_target: {
          repository_id: 7, repository: "owner/app", branch: "main", commit: "abcdef1", build_strategy: "PREBUILT_IMAGE",
          image: "ghcr.io/owner/app@sha256:1", min_replicas: 1, max_replicas: 1, container_port: 8080, env_names: [],
          config_fingerprint: "planfp12", recovered_from_deployment_id: 3, blocked_reasons: [] } } }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const result = await runCli(server, ["deployments", "propose-recovery", "3", "--reason", "readiness 실패"]);
  assert.equal(result.error, null, result.stderr);
  assert.deepEqual(requests, [{ method: "POST", url: "/api/v1/deployments/3/recovery-proposal", body: { reason: "readiness 실패" } }]);
  assert.match(result.stdout, /Needs Confirm\s+yes/);
  assert.match(result.stdout, /klepaas confirm 21/);
});

test("diagnose는 저장된 결과를 표시하고 --refresh면 새로 진단을 요청한다 (#117)", async (t) => {
  const urls = [];
  const server = createServer((request, response) => {
    urls.push(request.url);
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ status: "success", data: {
      deployment_id: 7, model: "rule", prompt_version: "no-evidence-v1", category: null, root_cause: null, abstain: true,
      summary: "근거 없음", observed_facts: [], hypotheses: [], next_checks: [], cited: [],
      warnings: ["관측 근거가 없어 모델을 호출하지 않았습니다"], reused: !request.url.includes("refresh"),
      diagnosed_at: "2026-10-11T16:00:00" } }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const saved = await runCli(server, ["deployments", "diagnose", "7"]);
  assert.equal(saved.error, null, saved.stderr);
  assert.match(saved.stdout, /2026-10-11T16:00:00 \(저장된 결과/);
  const fresh = await runCli(server, ["deployments", "diagnose", "7", "--refresh"]);
  assert.equal(fresh.error, null, fresh.stderr);
  assert.doesNotMatch(fresh.stdout, /저장된 결과/);
  assert.deepEqual(urls, ["/api/v1/deployments/7/diagnosis", "/api/v1/deployments/7/diagnosis?refresh=true"]);
});

test("knowledge put·incidents confirm·diagnose 지식 근거 (#104)", async (t) => {
  const requests = [];
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk) => (body += chunk));
    request.on("end", () => {
      requests.push({ method: request.method, url: request.url, body: body ? JSON.parse(body) : null });
      response.setHeader("Content-Type", "application/json");
      let data = null;
      if (request.url.includes("/knowledge/")) data = { slug: "runbook", title: "orders 운영", revision: 1, chunks: 2, updated_at: null };
      if (request.url.endsWith("/incident")) data = { deployment_id: 7, category: "ENV_CONFIG", root_cause: "required_env_missing" };
      if (request.url.endsWith("/diagnosis")) data = {
        deployment_id: 9, model: "gpt-6-luna", prompt_version: "diagnosis-v2-rag", category: "ENV_CONFIG",
        root_cause: "required_env_missing", abstain: false, summary: "요약", observed_facts: [], hypotheses: [], next_checks: [],
        cited: [], warnings: [], reused: false, diagnosed_at: null,
        knowledge: [{ source: "INCIDENT", ref: "incident:7", title: "사건: 배포 #7", repository: "owner/app",
          version: "commit abc1234 · img", updated_at: null, excerpt: "확정 원인: ENV_CONFIG" }] };
      response.end(JSON.stringify({ status: "success", data }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const dir = await mkdtemp(path.join(tmpdir(), "klepaas-knowledge-"));
  const file = path.join(dir, "runbook.md");
  await writeFile(file, "# orders 운영\n\nE_BOOT_17은 ADMIN_PASSWORD 누락\n");

  const put = await runCli(server, ["knowledge", "put", "--repository-id", "3", "--slug", "runbook", "--file", file]);
  assert.equal(put.error, null, put.stderr);
  const confirm = await runCli(server, ["incidents", "confirm", "7", "--category", "ENV_CONFIG", "--root-cause", "required_env_missing",
    "--resolution", "env 복구"]);
  assert.equal(confirm.error, null, confirm.stderr);
  const diagnose = await runCli(server, ["deployments", "diagnose", "9"]);
  assert.equal(diagnose.error, null, diagnose.stderr);

  assert.deepEqual(requests[0], { method: "PUT", url: "/api/v1/repositories/3/knowledge/runbook",
    body: { title: "orders 운영", content: "# orders 운영\n\nE_BOOT_17은 ADMIN_PASSWORD 누락\n" } });
  assert.deepEqual(requests[1], { method: "PUT", url: "/api/v1/deployments/7/incident",
    body: { category: "ENV_CONFIG", root_cause: "required_env_missing", resolution: "env 복구" } });
  assert.match(diagnose.stdout, /지식 근거:\n\s+\[INCIDENT\] 사건: 배포 #7 \(owner\/app, commit abc1234/);
});
