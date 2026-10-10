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
