// node --experimental-strip-types --test lib/deployment-config-form.test.ts (#106)
import { test } from "node:test"
import assert from "node:assert/strict"
import { describeChanges, toForm, toRequest, validate } from "./deployment-config-form.ts"
import type { DeploymentConfigResponse } from "./api"

// #55 샘플 앱 설정
const sample: DeploymentConfigResponse = {
  min_replicas: 1, max_replicas: 1, replica_count: 1,
  env_vars: { SAMPLE_GREETING: "hello" }, env_from_config_maps: [], env_from_secrets: [],
  container_port: 8080, domain_url: null, build_strategy: "GITHUB_ACTIONS_GHCR",
  image_uri_template: "ghcr.io/kjhyeon0620/klepaas-sample-node:sha-{commitHash}", image_pull_secret_name: null,
  service_type: "NODE_PORT", node_port: 30081, image_pull_secret_enabled: false,
  health_probe: { path: "/health", port: null, initial_delay_seconds: null, period_seconds: 5, failure_threshold: 3, startup_failure_threshold: null },
  resources: { cpu_request: null, cpu_limit: null, memory_request: null, memory_limit: null },
}

test("#55 샘플 앱 설정은 검사를 통과하고 같은 PUT 본문으로 돌아온다", () => {
  const form = toForm(sample)
  assert.deepEqual(validate(form), {})
  const body = toRequest(form)
  assert.equal(body.node_port, 30081)
  assert.equal(body.domain_url, "")
  assert.deepEqual(body.env_vars, { SAMPLE_GREETING: "hello" })
  assert.equal(body.image_pull_secret_enabled, false)
  assert.deepEqual(body.health_probe, { path: "/health", port: null, initial_delay_seconds: null, period_seconds: 5, failure_threshold: 3, startup_failure_threshold: null })
  assert.deepEqual(describeChanges(form, toForm(sample)), [])
})

test("서버 규칙: NodePort 필수·범위, 도메인, 포트, 레플리카", () => {
  const form = { ...toForm(sample), node_port: "", container_port: "0", min_replicas: "-1", max_replicas: "0" }
  const errors = validate(form)
  assert.match(errors.node_port!, /needs a NodePort/)
  assert.ok(errors.container_port && errors.min_replicas && errors.max_replicas)
  assert.match(validate({ ...toForm(sample), node_port: "29999" }).node_port!, /30000 to 32767/)

  const cluster = { ...toForm(sample), service_type: "CLUSTER_IP", domain_url: "" }
  assert.match(validate(cluster).domain_url!, /Required/)
  assert.match(validate({ ...cluster, domain_url: "https://app.example.com/x" }).domain_url!, /DNS host/)
  assert.deepEqual(validate({ ...cluster, domain_url: "App.Example.com" }), {})
  assert.equal(toRequest({ ...cluster, domain_url: "app.example.com", node_port: "30081" }).node_port, null)
})

test("env 이름 형식·중복·빈 이름은 행별로, envFrom 이름은 Kubernetes 형식(대문자 거절)", () => {
  const form = { ...toForm(sample), env: [{ name: "SAMPLE_GREETING", value: "a" }, { name: "SAMPLE_GREETING", value: "b" },
    { name: "1BAD", value: "" }, { name: " ", value: "x" }], env_from_secrets: "app-env, App-Env" }
  const errors = validate(form)
  assert.equal(errors["env.0"], undefined)
  assert.match(errors["env.1"]!, /Duplicate/)
  assert.match(errors["env.2"]!, /letters/)
  assert.match(errors["env.3"]!, /Enter a name/)
  assert.match(errors.env_from_secrets!, /App-Env/)
})

test("probe path는 /로 시작, 자원은 양수 수량이고 request ≤ limit (단위가 달라도 비교)", () => {
  assert.match(validate({ ...toForm(sample), probe_path: "health" }).probe_path!, /Start the path/)
  assert.deepEqual(validate({ ...toForm(sample), probe_path: "" }), {})
  assert.match(validate({ ...toForm(sample), cpu_request: "1", cpu_limit: "500m" }).cpu_request!, /exceed/)
  assert.deepEqual(validate({ ...toForm(sample), memory_request: "512Mi", memory_limit: "1Gi" }), {})
  assert.match(validate({ ...toForm(sample), memory_limit: "abc" }).memory_limit!, /positive quantity/)
  assert.match(validate({ ...toForm(sample), cpu_limit: "0" }).cpu_limit!, /positive quantity/)
})

test("변경 목록은 env 값을 보이지 않고 이름과 추가·변경·삭제만 보인다", () => {
  const before = toForm(sample)
  const after = { ...before, max_replicas: "2", env: [{ name: "SAMPLE_GREETING", value: "secret-new" }, { name: "TOKEN", value: "s3cret" }] }
  const changes = describeChanges(before, after)
  assert.deepEqual(changes, [
    { label: "Max replicas", before: "1", after: "2" },
    { label: "Env SAMPLE_GREETING", before: "set", after: "value changed" },
    { label: "Env TOKEN", before: "(none)", after: "added" },
  ])
  assert.ok(!JSON.stringify(changes).includes("s3cret") && !JSON.stringify(changes).includes("secret-new"))
  assert.deepEqual(describeChanges(after, before).at(-1), { label: "Env TOKEN", before: "set", after: "removed" })
})
