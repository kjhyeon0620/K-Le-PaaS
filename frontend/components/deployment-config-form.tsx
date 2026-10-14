"use client"

// 배포 설정 입력 (#106). 한 화면·한 번의 저장, 저장 전 변경 확인, 서버와 같은 규칙의 미리 검사.
// 열 때의 설정으로 시작하고, 부모가 30초마다 다시 불러와도 입력 중인 값을 덮어쓰지 않는다.
import { useMemo, useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { AlertTriangle, Eye, EyeOff, Plus, Save, Trash2 } from "lucide-react"
import { api, type DeploymentConfigResponse } from "@/lib/api"
import {
  describeChanges,
  toForm,
  toRequest,
  validate,
  type ConfigErrors,
  type ConfigForm,
} from "@/lib/deployment-config-form"

interface DeploymentConfigFormProps {
  owner: string
  repo: string
  config: DeploymentConfigResponse
  onSaved: (config: DeploymentConfigResponse) => void
}

type TextField = Exclude<keyof ConfigForm, "env" | "image_pull_secret_enabled">

export function DeploymentConfigForm({ owner, repo, config, onSaved }: DeploymentConfigFormProps) {
  const [baseline, setBaseline] = useState<ConfigForm>(() => toForm(config))
  const [form, setForm] = useState<ConfigForm>(() => toForm(config))
  const [errors, setErrors] = useState<ConfigErrors>({})
  const [serverError, setServerError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [shownValues, setShownValues] = useState<Set<number>>(new Set())

  const changes = useMemo(() => describeChanges(baseline, form), [baseline, form])
  const nodePort = form.service_type === "NODE_PORT"

  const set = <K extends keyof ConfigForm>(key: K, value: ConfigForm[K]) => {
    setForm((current) => ({ ...current, [key]: value }))
    setErrors((current) => ({ ...current, [key]: undefined }))
  }

  const setEnv = (index: number, row: Partial<ConfigForm["env"][number]>) => {
    setForm((current) => ({ ...current, env: current.env.map((r, i) => (i === index ? { ...r, ...row } : r)) }))
    setErrors((current) => ({ ...current, [`env.${index}`]: undefined }))
  }

  const removeEnv = (index: number) => {
    setForm((current) => ({ ...current, env: current.env.filter((_, i) => i !== index) }))
    setErrors({})
    setShownValues(new Set())
  }

  const handleReview = () => {
    const found = validate(form)
    setErrors(found)
    setServerError(null)
    if (Object.values(found).some(Boolean)) return
    setConfirmOpen(true)
  }

  const handleSave = async () => {
    setConfirmOpen(false)
    setSaving(true)
    setServerError(null)
    try {
      const saved = await api.saveDeploymentConfig(owner, repo, toRequest(form))
      setBaseline(toForm(saved))
      setForm(toForm(saved))
      onSaved(saved)
    } catch (error) {
      // 서버가 거절한 이유를 저장 버튼 근처에 두고 입력값은 그대로 둔다 (#106 Q1)
      setServerError(error instanceof Error ? error.message : "Failed to save deployment settings.")
    } finally {
      setSaving(false)
    }
  }

  const field = (key: TextField, label: string, options: { hint?: string; placeholder?: string; mono?: boolean; inputMode?: "numeric" } = {}) => (
    <div className="space-y-1">
      <Label htmlFor={`config-${key}`}>{label}</Label>
      <Input
        id={`config-${key}`}
        value={form[key]}
        onChange={(event) => set(key, event.target.value)}
        placeholder={options.placeholder}
        inputMode={options.inputMode}
        aria-invalid={Boolean(errors[key])}
        aria-describedby={errors[key] ? `config-${key}-error` : undefined}
        className={options.mono ? "font-mono text-sm" : undefined}
      />
      {errors[key] ? (
        <p id={`config-${key}-error`} className="text-xs text-destructive">{errors[key]}</p>
      ) : options.hint ? (
        <p className="text-xs text-muted-foreground">{options.hint}</p>
      ) : null}
    </div>
  )

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Build &amp; image</CardTitle>
          <CardDescription>How the image is built or chosen. KANIKO builds from source; the other two deploy an image built elsewhere.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <Label htmlFor="config-build_strategy">Build strategy</Label>
              <Select value={form.build_strategy} onValueChange={(value) => set("build_strategy", value)}>
                <SelectTrigger id="config-build_strategy" className="w-full">
                  <SelectValue placeholder="Select" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="KANIKO">KANIKO</SelectItem>
                  <SelectItem value="GITHUB_ACTIONS_GHCR">GITHUB_ACTIONS_GHCR</SelectItem>
                  <SelectItem value="PREBUILT_IMAGE">PREBUILT_IMAGE</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {field("image_uri_template", "Image URI template", {
              mono: true,
              placeholder: "ghcr.io/owner/app:sha-{commitHash}",
              hint: "Needed for GITHUB_ACTIONS_GHCR and PREBUILT_IMAGE. {commitHash} is replaced per deployment.",
            })}
          </div>
          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <Label htmlFor="config-image_pull_secret_enabled">Use image pull secret</Label>
              <p className="text-xs text-muted-foreground">Turn off for public images so an expired registry credential cannot block deployments.</p>
            </div>
            <Switch
              id="config-image_pull_secret_enabled"
              checked={form.image_pull_secret_enabled}
              onCheckedChange={(checked) => set("image_pull_secret_enabled", checked)}
            />
          </div>
          {form.image_pull_secret_enabled &&
            field("image_pull_secret_name", "Image pull secret name", {
              mono: true,
              hint: "Leave empty for the platform default. The server rejects names the operator has not allowed.",
            })}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Service &amp; replicas</CardTitle>
          <CardDescription>How the app is exposed and how many pods run.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <Label htmlFor="config-service_type">Service type</Label>
              <Select value={form.service_type} onValueChange={(value) => set("service_type", value)}>
                <SelectTrigger id="config-service_type" className="w-full">
                  <SelectValue placeholder="Select" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CLUSTER_IP">CLUSTER_IP</SelectItem>
                  <SelectItem value="NODE_PORT">NODE_PORT</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {nodePort ? field("node_port", "NodePort", { inputMode: "numeric", placeholder: "30000-32767" }) : <div className="hidden sm:block" />}
            {field("container_port", "Container port", { inputMode: "numeric", placeholder: "8080" })}
            {field("domain_url", "Domain", {
              mono: true,
              placeholder: "app.example.com",
              hint: nodePort ? "Optional for NodePort-only apps; leave empty for no Ingress." : "Host name only, without https:// or a path.",
            })}
            {field("min_replicas", "Min replicas", { inputMode: "numeric" })}
            {field("max_replicas", "Max replicas", { inputMode: "numeric" })}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Environment variables</CardTitle>
          <CardDescription>
            Values are stored with the deployment settings. Put passwords and tokens in a Kubernetes Secret and list it under Secret names.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {form.env.length === 0 && <p className="text-sm text-muted-foreground">No environment variables.</p>}
          {form.env.map((row, index) => (
            <div key={index} className="space-y-1">
              {/* 모바일은 이름을 한 줄에 두고 값·버튼을 다음 줄에 둔다 */}
              <div className="grid grid-cols-[minmax(0,1fr)_auto_auto] items-end gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto_auto]">
                <div className="col-span-3 space-y-1 sm:col-span-1">
                  <Label htmlFor={`config-env-name-${index}`}>Name</Label>
                  <Input
                    id={`config-env-name-${index}`}
                    value={row.name}
                    onChange={(event) => setEnv(index, { name: event.target.value })}
                    aria-invalid={Boolean(errors[`env.${index}`])}
                    className="font-mono text-sm"
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor={`config-env-value-${index}`}>Value</Label>
                  <Input
                    id={`config-env-value-${index}`}
                    type={shownValues.has(index) ? "text" : "password"}
                    autoComplete="off"
                    value={row.value}
                    onChange={(event) => setEnv(index, { value: event.target.value })}
                    className="font-mono text-sm"
                  />
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={shownValues.has(index) ? `Hide value of ${row.name || "variable"}` : `Show value of ${row.name || "variable"}`}
                  onClick={() => setShownValues((current) => {
                    const next = new Set(current)
                    if (next.has(index)) next.delete(index)
                    else next.add(index)
                    return next
                  })}
                >
                  {shownValues.has(index) ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </Button>
                <Button type="button" variant="ghost" size="icon" aria-label={`Remove ${row.name || "variable"}`} onClick={() => removeEnv(index)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
              {errors[`env.${index}`] && <p className="text-xs text-destructive">{errors[`env.${index}`]}</p>}
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" onClick={() => set("env", [...form.env, { name: "", value: "" }])}>
            <Plus className="mr-2 h-4 w-4" />
            Add variable
          </Button>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {(["env_from_config_maps", "env_from_secrets"] as const).map((key) => (
              <div key={key} className="space-y-1">
                <Label htmlFor={`config-${key}`}>{key === "env_from_config_maps" ? "ConfigMap names" : "Secret names"}</Label>
                <Textarea
                  id={`config-${key}`}
                  value={form[key]}
                  onChange={(event) => set(key, event.target.value)}
                  aria-invalid={Boolean(errors[key])}
                  className="min-h-16 font-mono text-sm"
                />
                {errors[key] ? (
                  <p className="text-xs text-destructive">{errors[key]}</p>
                ) : (
                  <p className="text-xs text-muted-foreground">Kubernetes object names, separated by commas or new lines. Only names are stored.</p>
                )}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Health probe &amp; resources</CardTitle>
          <CardDescription>
            The readiness probe keeps a deployment from succeeding until the app answers on this path. Leave the path empty for no probe and resources empty for no requests/limits.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {field("probe_path", "Health path", { mono: true, placeholder: "/health" })}
            {field("probe_port", "Probe port", { inputMode: "numeric", placeholder: "container port" })}
            {field("probe_initial_delay_seconds", "Initial delay (s)", { inputMode: "numeric", placeholder: "0" })}
            {field("probe_period_seconds", "Period (s)", { inputMode: "numeric", placeholder: "10" })}
            {field("probe_failure_threshold", "Failure threshold", { inputMode: "numeric", placeholder: "3" })}
            {field("probe_startup_failure_threshold", "Startup failure threshold", { inputMode: "numeric", placeholder: "off" })}
          </div>
          <p className="text-xs text-muted-foreground">The startup probe uses the same path; it allows period × startup failure threshold seconds to boot.</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {field("cpu_request", "CPU request", { mono: true, placeholder: "100m" })}
            {field("cpu_limit", "CPU limit", { mono: true, placeholder: "500m" })}
            {field("memory_request", "Memory request", { mono: true, placeholder: "128Mi" })}
            {field("memory_limit", "Memory limit", { mono: true, placeholder: "256Mi" })}
          </div>
        </CardContent>
      </Card>

      <div className="space-y-2">
        {Object.values(errors).some(Boolean) && (
          <p className="text-sm text-destructive">Fix the highlighted fields before saving.</p>
        )}
        {serverError && (
          <p role="alert" className="flex items-start gap-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span className="break-words">The server rejected the settings: {serverError}</span>
          </p>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={handleReview} disabled={saving || changes.length === 0}>
            <Save className="mr-2 h-4 w-4" />
            {saving ? "Saving..." : changes.length === 0 ? "No changes" : `Review ${changes.length} change${changes.length === 1 ? "" : "s"}`}
          </Button>
          <Button variant="ghost" onClick={() => { setForm(baseline); setErrors({}); setServerError(null) }} disabled={saving || changes.length === 0}>
            Discard
          </Button>
          <p className="text-xs text-muted-foreground">Settings apply on the next deployment.</p>
        </div>
      </div>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent className="max-w-lg">
          <AlertDialogHeader>
            <AlertDialogTitle>Save deployment settings?</AlertDialogTitle>
            <AlertDialogDescription>These changes apply on the next deployment of {owner}/{repo}. Environment values are not shown.</AlertDialogDescription>
          </AlertDialogHeader>
          <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
            {changes.map((change) => (
              <li key={change.label} className="grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2">
                <span className="text-muted-foreground">{change.label}</span>
                <span className="break-all font-mono text-xs">{change.before} → {change.after}</span>
              </li>
            ))}
          </ul>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep editing</AlertDialogCancel>
            <AlertDialogAction onClick={handleSave}>Save</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
