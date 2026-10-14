// 콘솔 배포 설정 폼 (#106). 입력값 ↔ PUT /api/v1/repositories/{id}/config 본문, 서버와 같은 규칙의 미리 검사, 저장 전 변경 목록.
// 서버가 최종 판정이다. 여기 규칙은 서버(UpdateDeploymentConfigRequest, RepositoryService, HealthProbe)를 따라 쓴 것이다.
// 화면 컴포넌트와 분리해 node --test로 검사한다 (lib/deployment-config-form.test.ts).
import type { DeploymentConfigResponse } from "./api"

export interface EnvRow {
  name: string
  value: string
}

export interface ConfigForm {
  build_strategy: string
  image_uri_template: string
  image_pull_secret_enabled: boolean
  image_pull_secret_name: string
  service_type: string
  node_port: string
  container_port: string
  domain_url: string
  min_replicas: string
  max_replicas: string
  env: EnvRow[]
  env_from_config_maps: string
  env_from_secrets: string
  probe_path: string
  probe_port: string
  probe_initial_delay_seconds: string
  probe_period_seconds: string
  probe_failure_threshold: string
  probe_startup_failure_threshold: string
  cpu_request: string
  cpu_limit: string
  memory_request: string
  memory_limit: string
}

/** 항목별 오류. env 행은 `env.<index>` */
export type ConfigErrors = Partial<Record<keyof ConfigForm | `env.${number}`, string>>

export interface ConfigChange {
  label: string
  before: string
  after: string
}

const text = (value: string | number | null | undefined) => (value == null ? "" : String(value))

export function toForm(config: DeploymentConfigResponse): ConfigForm {
  const probe = config.health_probe
  const resources = config.resources
  return {
    build_strategy: config.build_strategy ?? "",
    image_uri_template: config.image_uri_template ?? "",
    image_pull_secret_enabled: config.image_pull_secret_enabled ?? true,
    image_pull_secret_name: config.image_pull_secret_name ?? "",
    service_type: config.service_type ?? "CLUSTER_IP",
    node_port: text(config.node_port),
    container_port: text(config.container_port),
    domain_url: config.domain_url ?? "",
    min_replicas: text(config.min_replicas),
    max_replicas: text(config.max_replicas),
    env: Object.entries(config.env_vars ?? {})
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([name, value]) => ({ name, value })),
    env_from_config_maps: (config.env_from_config_maps ?? []).join(", "),
    env_from_secrets: (config.env_from_secrets ?? []).join(", "),
    probe_path: probe?.path ?? "",
    probe_port: text(probe?.port),
    probe_initial_delay_seconds: text(probe?.initial_delay_seconds),
    probe_period_seconds: text(probe?.period_seconds),
    probe_failure_threshold: text(probe?.failure_threshold),
    probe_startup_failure_threshold: text(probe?.startup_failure_threshold),
    cpu_request: resources?.cpu_request ?? "",
    cpu_limit: resources?.cpu_limit ?? "",
    memory_request: resources?.memory_request ?? "",
    memory_limit: resources?.memory_limit ?? "",
  }
}

const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const DNS_LABEL = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/
// Kubernetes 수량 (Fabric8 Quantity가 읽는 형식 중 콘솔에서 쓰는 것)
const QUANTITY = /^(\d+(\.\d+)?|\.\d+)(m|k|Ki|M|Mi|G|Gi|T|Ti|P|Pi|E|Ei)?$/
const SUFFIX: Record<string, number> = {
  "": 1, m: 1e-3, k: 1e3, M: 1e6, G: 1e9, T: 1e12, P: 1e15, E: 1e18,
  Ki: 2 ** 10, Mi: 2 ** 20, Gi: 2 ** 30, Ti: 2 ** 40, Pi: 2 ** 50, Ei: 2 ** 60,
}

function isInteger(value: string): boolean {
  return /^\d+$/.test(value.trim())
}

// 서버 isValidKubernetesObjectName과 같다 (소문자로 바꾸지 않는다)
function isKubernetesName(name: string): boolean {
  if (name.length > 253 || name.startsWith(".") || name.endsWith(".")) return false
  return name.split(".").every((label) => label.length <= 63 && DNS_LABEL.test(label))
}

// 서버는 domain_url을 소문자로 바꾼 뒤 같은 규칙으로 본다
function isDnsHost(value: string): boolean {
  return isKubernetesName(value.trim().toLowerCase())
}

function quantity(value: string): number | null {
  const match = QUANTITY.exec(value.trim())
  if (!match) return null
  return Number(match[1]) * SUFFIX[match[3] ?? ""]
}

export function splitNames(value: string): string[] {
  return Array.from(new Set(value.split(/[,\n]/).map((name) => name.trim()).filter(Boolean)))
}

function integerAtLeast(errors: ConfigErrors, form: ConfigForm, key: keyof ConfigForm, min: number, required: boolean, max?: number) {
  const value = String(form[key]).trim()
  if (value === "") {
    if (required) errors[key] = "Required."
    return
  }
  if (!isInteger(value) || Number(value) < min || (max != null && Number(value) > max)) {
    errors[key] = max == null ? `Enter a whole number of at least ${min}.` : `Enter a whole number from ${min} to ${max}.`
  }
}

export function validate(form: ConfigForm): ConfigErrors {
  const errors: ConfigErrors = {}
  integerAtLeast(errors, form, "container_port", 1, true, 65535)
  integerAtLeast(errors, form, "min_replicas", 0, true)
  integerAtLeast(errors, form, "max_replicas", 1, true)

  const nodePort = form.service_type === "NODE_PORT"
  if (nodePort) {
    if (form.node_port.trim() === "") errors.node_port = "A NodePort service needs a NodePort."
    else integerAtLeast(errors, form, "node_port", 30000, true, 32767)
  }
  if (form.domain_url.trim() === "") {
    if (!nodePort) errors.domain_url = "Required unless the service is NodePort only."
  } else if (!isDnsHost(form.domain_url)) {
    errors.domain_url = "Enter a DNS host name such as app.example.com (no scheme or path)."
  }

  for (const key of ["env_from_config_maps", "env_from_secrets"] as const) {
    const invalid = splitNames(form[key]).filter((name) => !isKubernetesName(name))
    if (invalid.length) errors[key] = `Not a Kubernetes object name: ${invalid.join(", ")}`
  }

  const seen = new Set<string>()
  form.env.forEach((row, index) => {
    const name = row.name.trim()
    if (name === "") errors[`env.${index}`] = "Enter a name or remove the row."
    else if (!ENV_NAME.test(name)) errors[`env.${index}`] = "Use letters, digits and underscores, not starting with a digit."
    else if (seen.has(name)) errors[`env.${index}`] = `Duplicate name: ${name}`
    seen.add(name)
  })

  if (form.probe_path.trim() !== "" && !/^\/\S*$/.test(form.probe_path.trim())) {
    errors.probe_path = "Start the path with / (no spaces)."
  }
  integerAtLeast(errors, form, "probe_port", 1, false, 65535)
  integerAtLeast(errors, form, "probe_initial_delay_seconds", 0, false)
  integerAtLeast(errors, form, "probe_period_seconds", 1, false)
  integerAtLeast(errors, form, "probe_failure_threshold", 1, false)
  integerAtLeast(errors, form, "probe_startup_failure_threshold", 1, false)

  for (const [request, limit] of [["cpu_request", "cpu_limit"], ["memory_request", "memory_limit"]] as const) {
    for (const key of [request, limit]) {
      const value = form[key].trim()
      if (value !== "" && !(quantity(value)! > 0)) errors[key] = "Enter a positive quantity such as 100m, 0.5, 128Mi or 1Gi."
    }
    const r = quantity(form[request]), l = quantity(form[limit])
    if (!errors[request] && !errors[limit] && r != null && l != null && r > l) errors[request] = "Request cannot exceed the limit."
  }
  return errors
}

const orNull = (value: string) => (value.trim() === "" ? null : value.trim())
const numberOrNull = (value: string) => (value.trim() === "" ? null : Number(value.trim()))

/** 저장 본문. 모든 항목을 보낸다 (한 번의 PUT, #106 Q2). probe path가 비면 서버가 probe를 해제한다 */
export function toRequest(form: ConfigForm) {
  const nodePort = form.service_type === "NODE_PORT"
  return {
    min_replicas: Number(form.min_replicas),
    max_replicas: Number(form.max_replicas),
    env_vars: Object.fromEntries(form.env.map((row) => [row.name.trim(), row.value])),
    env_from_config_maps: splitNames(form.env_from_config_maps),
    env_from_secrets: splitNames(form.env_from_secrets),
    container_port: Number(form.container_port),
    domain_url: form.domain_url.trim(),
    build_strategy: orNull(form.build_strategy),
    image_uri_template: orNull(form.image_uri_template),
    image_pull_secret_name: orNull(form.image_pull_secret_name),
    image_pull_secret_enabled: form.image_pull_secret_enabled,
    service_type: orNull(form.service_type),
    node_port: nodePort ? numberOrNull(form.node_port) : null,
    health_probe: {
      path: form.probe_path.trim(),
      port: numberOrNull(form.probe_port),
      initial_delay_seconds: numberOrNull(form.probe_initial_delay_seconds),
      period_seconds: numberOrNull(form.probe_period_seconds),
      failure_threshold: numberOrNull(form.probe_failure_threshold),
      startup_failure_threshold: numberOrNull(form.probe_startup_failure_threshold),
    },
    resources: {
      cpu_request: orNull(form.cpu_request),
      cpu_limit: orNull(form.cpu_limit),
      memory_request: orNull(form.memory_request),
      memory_limit: orNull(form.memory_limit),
    },
  }
}

const LABELS: Array<[keyof ConfigForm, string]> = [
  ["build_strategy", "Build strategy"],
  ["image_uri_template", "Image URI template"],
  ["image_pull_secret_enabled", "Use image pull secret"],
  ["image_pull_secret_name", "Image pull secret name"],
  ["service_type", "Service type"],
  ["node_port", "NodePort"],
  ["container_port", "Container port"],
  ["domain_url", "Domain"],
  ["min_replicas", "Min replicas"],
  ["max_replicas", "Max replicas"],
  ["env_from_config_maps", "ConfigMap names"],
  ["env_from_secrets", "Secret names"],
  ["probe_path", "Health path"],
  ["probe_port", "Probe port"],
  ["probe_initial_delay_seconds", "Initial delay (s)"],
  ["probe_period_seconds", "Period (s)"],
  ["probe_failure_threshold", "Failure threshold"],
  ["probe_startup_failure_threshold", "Startup failure threshold"],
  ["cpu_request", "CPU request"],
  ["cpu_limit", "CPU limit"],
  ["memory_request", "Memory request"],
  ["memory_limit", "Memory limit"],
]

/** 저장 전 확인 목록. env는 값을 보이지 않고 이름만 보인다 */
export function describeChanges(before: ConfigForm, after: ConfigForm): ConfigChange[] {
  const show = (value: ConfigForm[keyof ConfigForm]) => {
    if (typeof value === "boolean") return value ? "on" : "off"
    const s = String(value).trim()
    return s === "" ? "(empty)" : s
  }
  const normalized = (form: ConfigForm, key: keyof ConfigForm) =>
    key === "env_from_config_maps" || key === "env_from_secrets" ? splitNames(form[key]).join(", ") : show(form[key])
  const changes: ConfigChange[] = []
  for (const [key, label] of LABELS) {
    if (key === "node_port" && after.service_type !== "NODE_PORT" && before.service_type !== "NODE_PORT") continue
    const b = normalized(before, key), a = normalized(after, key)
    if (b !== a) changes.push({ label, before: b || "(empty)", after: a || "(empty)" })
  }
  const oldEnv: Record<string, string> = Object.fromEntries(before.env.map((row) => [row.name.trim(), row.value]))
  const newEnv: Record<string, string> = Object.fromEntries(after.env.map((row) => [row.name.trim(), row.value]))
  Object.keys(newEnv).forEach((name) => {
    if (!(name in oldEnv)) changes.push({ label: `Env ${name}`, before: "(none)", after: "added" })
    else if (oldEnv[name] !== newEnv[name]) changes.push({ label: `Env ${name}`, before: "set", after: "value changed" })
  })
  Object.keys(oldEnv).forEach((name) => {
    if (!(name in newEnv)) changes.push({ label: `Env ${name}`, before: "set", after: "removed" })
  })
  return changes
}
