"use client"

import { useState, useEffect } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Progress } from "@/components/ui/progress"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  GitBranch,
  Play,
  Pause,
  RotateCcw,
  Scale,
  Eye,
  AlertTriangle,
  CheckCircle,
  Clock,
  Server,
  Cpu,
  HardDrive,
  RefreshCw,
  Github,
  Activity,
  ExternalLink,
  Terminal,
  Save,
} from "lucide-react"
import { api, type DeploymentConfigResponse, type DeploymentStatus, type RepositoryWorkload } from "@/lib/api"
import { useAuth } from "@/contexts/auth-context"
import { useToast } from "@/hooks/use-toast"
import { RollbackDialog } from "@/components/rollback-dialog"
import { ScaleDialog } from "@/components/scale-dialog"
import { RestartDialog } from "@/components/restart-dialog"
import { DeploymentLogsDialog } from "@/components/deployment-logs-dialog"
import { formatDuration } from "@/lib/utils"
import { formatImageDisplay } from "@/lib/utils/image-formatter"

interface DeploymentStatusMonitoringProps {
  onNavigateToMonitoring?: () => void
  onNavigateToPipelines?: () => void
  // GitHub 화면의 Configure에서 넘어올 때 열 저장소 (owner/repo)
  configureRepo?: string | null
  onConfigureRepoHandled?: () => void
}

const IN_PROGRESS_STATUSES: readonly DeploymentStatus[] = ["PENDING", "UPLOADING_SOURCE", "BUILDING", "DEPLOYING"]

type BuildServiceInput = {
  build_strategy: string
  image_uri_template: string
  image_pull_secret_name: string
  service_type: string
  node_port: string
  container_port: string
  domain_url: string
}

function toBuildServiceInput(config: DeploymentConfigResponse | undefined): BuildServiceInput {
  return {
    build_strategy: config?.build_strategy ?? "",
    image_uri_template: config?.image_uri_template ?? "",
    image_pull_secret_name: config?.image_pull_secret_name ?? "",
    service_type: config?.service_type ?? "CLUSTER_IP",
    node_port: config?.node_port == null ? "" : String(config.node_port),
    container_port: config ? String(config.container_port) : "",
    domain_url: config?.domain_url ?? "",
  }
}

// 백엔드 LocalDateTime은 시간대 없이 서버 시각(UTC)으로 직렬화된다
function parseServerTime(value: string | null | undefined): Date | null {
  if (!value) return null
  return new Date(/[zZ]|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`)
}

function deploymentDurationSeconds(startedAt: string | null, finishedAt: string | null): number | null {
  const start = parseServerTime(startedAt)
  const end = parseServerTime(finishedAt)
  if (!start || !end) return null
  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 1000))
}

const PROBE_FIELDS = [
  ["path", "Health path", "/health"],
  ["port", "Probe port", "container port"],
  ["initial_delay_seconds", "Initial delay (s)", "0"],
  ["period_seconds", "Period (s)", "10"],
  ["failure_threshold", "Failure threshold", "3"],
  ["startup_failure_threshold", "Startup failure threshold", "off"],
] as const
const RESOURCE_FIELDS = [
  ["cpu_request", "CPU request", "100m"],
  ["cpu_limit", "CPU limit", "500m"],
  ["memory_request", "Memory request", "128Mi"],
  ["memory_limit", "Memory limit", "256Mi"],
] as const
type RuntimeCheckField = (typeof PROBE_FIELDS)[number][0] | (typeof RESOURCE_FIELDS)[number][0]
type RuntimeCheckInput = Record<RuntimeCheckField, string>

function toRuntimeCheckInput(config: DeploymentConfigResponse | undefined): RuntimeCheckInput {
  const values: Record<string, string | number | null | undefined> = {
    ...config?.health_probe,
    ...config?.resources,
  }
  const fields = [...PROBE_FIELDS, ...RESOURCE_FIELDS].map(([key]) => [key, String(values[key] ?? "")])
  return Object.fromEntries(fields) as RuntimeCheckInput
}

function toNumberOrNull(value: string): number | null {
  return value.trim() === "" ? null : Number(value)
}

function toTextOrNull(value: string): string | null {
  return value.trim() === "" ? null : value.trim()
}

function parseEnvFromNames(value: string): string[] {
  const names = value
    .split(/[,\n]/)
    .map((name) => name.trim())
    .filter(Boolean)

  return Array.from(new Set(names))
}

export function DeploymentStatusMonitoring({
  onNavigateToMonitoring,
  onNavigateToPipelines,
  configureRepo,
  onConfigureRepoHandled,
}: DeploymentStatusMonitoringProps = {}) {
  const [repositories, setRepositories] = useState<RepositoryWorkload[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedRepo, setSelectedRepo] = useState<RepositoryWorkload | null>(null)
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [deploymentConfigs, setDeploymentConfigs] = useState<Record<string, DeploymentConfigResponse>>({})
  const [configErrors, setConfigErrors] = useState<Record<string, string>>({})
  const [loadError, setLoadError] = useState<string | null>(null)
  const [detailsTab, setDetailsTab] = useState("status")
  const [buildServiceInput, setBuildServiceInput] = useState<BuildServiceInput>(toBuildServiceInput(undefined))
  const [envFromConfigMapsInput, setEnvFromConfigMapsInput] = useState("")
  const [envFromSecretsInput, setEnvFromSecretsInput] = useState("")
  const [runtimeCheckInput, setRuntimeCheckInput] = useState<RuntimeCheckInput>(toRuntimeCheckInput(undefined))
  const [configSaving, setConfigSaving] = useState(false)

  // Dialog states for Rollback, Scale, Restart, Logs
  const [rollbackDialogOpen, setRollbackDialogOpen] = useState(false)
  const [scaleDialogOpen, setScaleDialogOpen] = useState(false)
  const [restartDialogOpen, setRestartDialogOpen] = useState(false)
  const [logsDialogOpen, setLogsDialogOpen] = useState(false)
  const [actionRepo, setActionRepo] = useState<RepositoryWorkload | null>(null)

  // 사용자 인증 상태 확인
  const { user, isLoading: authLoading } = useAuth()
  const { toast } = useToast()

  const fetchRepositories = async () => {
    try {
      setLoading(true)
      const response = await api.getRepositoriesLatestDeployments()
      setRepositories(response.repositories)
      setLoadError(null)

      // 설정 조회에 실패한 저장소는 기본값을 만들지 않고 오류로 표시한다 (#66)
      const configs: Record<string, DeploymentConfigResponse> = {}
      const errors: Record<string, string> = {}
      await Promise.all(
        response.repositories.map(async (repo) => {
          try {
            configs[repo.full_name] = await api.getDeploymentConfig(repo.owner, repo.repo)
          } catch (error) {
            errors[repo.full_name] = error instanceof Error ? error.message : "Failed to load deployment config."
          }
        })
      )
      setDeploymentConfigs(configs)
      setConfigErrors(errors)
    } catch (error) {
      console.error("Failed to fetch repositories:", error)
      setLoadError(error instanceof Error ? error.message : "Failed to load repositories.")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchRepositories()
    // Poll every 30 seconds
    const interval = setInterval(fetchRepositories, 30000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    if (!selectedRepo || !detailsOpen) {
      return
    }
    const config = deploymentConfigs[selectedRepo.full_name]
    setEnvFromConfigMapsInput(config?.env_from_config_maps.join(", ") ?? "")
    setEnvFromSecretsInput(config?.env_from_secrets.join(", ") ?? "")
    setRuntimeCheckInput(toRuntimeCheckInput(config))
    setBuildServiceInput(toBuildServiceInput(config))
  }, [deploymentConfigs, detailsOpen, selectedRepo])

  // GitHub 화면의 Configure: 목록을 불러온 뒤 해당 저장소의 설정 탭을 연다
  useEffect(() => {
    if (!configureRepo || loading) {
      return
    }
    const repo = repositories.find((candidate) => candidate.full_name === configureRepo)
    if (repo) {
      setSelectedRepo(repo)
      setDetailsTab("config")
      setDetailsOpen(true)
    }
    onConfigureRepoHandled?.()
  }, [configureRepo, loading, repositories, onConfigureRepoHandled])

  // 배포 기록의 상태다. Pod가 지금 실행 중인지는 관측하지 않으므로 "Running"으로 표시하지 않는다.
  const getStatusBadge = (status: DeploymentStatus | undefined) => {
    if (!status) {
      return <Badge variant="outline">No Deployments</Badge>
    }
    if (status === "SUCCESS") {
      return (
        <Badge className="bg-green-500">
          <CheckCircle className="mr-1 h-3 w-3" />
          Succeeded
        </Badge>
      )
    }
    if (status === "FAILED") {
      return (
        <Badge variant="destructive">
          <AlertTriangle className="mr-1 h-3 w-3" />
          Failed
        </Badge>
      )
    }
    if (IN_PROGRESS_STATUSES.includes(status)) {
      return (
        <Badge className="bg-blue-500">
          <Clock className="mr-1 h-3 w-3" />
          {status}
        </Badge>
      )
    }
    return <Badge variant="outline">{status}</Badge>
  }

  const formatTime = (value: string | null) => {
    const date = parseServerTime(value)
    if (!date) return "-"
    const diffMins = Math.floor((Date.now() - date.getTime()) / 60000)

    if (diffMins < 1) return "Just now"
    if (diffMins < 60) return `${diffMins}m ago`
    const diffHours = Math.floor(diffMins / 60)
    if (diffHours < 24) return `${diffHours}h ago`
    const diffDays = Math.floor(diffHours / 24)
    return `${diffDays}d ago`
  }

  const handleViewDetails = (repo: RepositoryWorkload) => {
    setSelectedRepo(repo)
    setDetailsTab("status")
    setDetailsOpen(true)
  }

  const handleSaveBuildAndService = async () => {
    if (!selectedRepo) {
      return
    }

    try {
      setConfigSaving(true)
      const input = buildServiceInput
      const updatedConfig = await api.updateDeploymentBuildAndService(selectedRepo.owner, selectedRepo.repo, {
        build_strategy: toTextOrNull(input.build_strategy),
        image_uri_template: toTextOrNull(input.image_uri_template),
        image_pull_secret_name: toTextOrNull(input.image_pull_secret_name),
        service_type: toTextOrNull(input.service_type),
        node_port: input.service_type === "NODE_PORT" ? toNumberOrNull(input.node_port) : null,
        container_port: Number(input.container_port),
        domain_url: toTextOrNull(input.domain_url),
      })
      setDeploymentConfigs((current) => ({
        ...current,
        [selectedRepo.full_name]: updatedConfig,
      }))
      toast({
        title: "Build and service settings saved",
        description: "The settings apply on the next deployment.",
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to save build and service settings."
      toast({
        title: "Save failed",
        description: message,
        variant: "destructive",
      })
    } finally {
      setConfigSaving(false)
    }
  }

  const handleOpenLogs = (repo: RepositoryWorkload) => {
    setActionRepo(repo)
    setLogsDialogOpen(true)
  }

  const handleOpenRollback = (repo: RepositoryWorkload) => {
    setActionRepo(repo)
    setRollbackDialogOpen(true)
  }

  const handleOpenScale = (repo: RepositoryWorkload) => {
    setActionRepo(repo)
    setScaleDialogOpen(true)
  }

  const handleOpenRestart = (repo: RepositoryWorkload) => {
    setActionRepo(repo)
    setRestartDialogOpen(true)
  }

  const handleActionSuccess = () => {
    // Refresh repositories after successful action
    fetchRepositories()
  }

  const handleSaveProbeAndResources = async () => {
    if (!selectedRepo) {
      return
    }

    try {
      setConfigSaving(true)
      const input = runtimeCheckInput
      const updatedConfig = await api.updateDeploymentProbeAndResources(
        selectedRepo.owner,
        selectedRepo.repo,
        {
          path: input.path.trim(),
          port: toNumberOrNull(input.port),
          initial_delay_seconds: toNumberOrNull(input.initial_delay_seconds),
          period_seconds: toNumberOrNull(input.period_seconds),
          failure_threshold: toNumberOrNull(input.failure_threshold),
          startup_failure_threshold: toNumberOrNull(input.startup_failure_threshold),
        },
        {
          cpu_request: toTextOrNull(input.cpu_request),
          cpu_limit: toTextOrNull(input.cpu_limit),
          memory_request: toTextOrNull(input.memory_request),
          memory_limit: toTextOrNull(input.memory_limit),
        }
      )
      setDeploymentConfigs((current) => ({
        ...current,
        [selectedRepo.full_name]: updatedConfig,
      }))
      toast({
        title: "Health probe and resources saved",
        description: "The settings apply on the next deployment.",
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to save health probe and resource settings."
      toast({
        title: "Save failed",
        description: message,
        variant: "destructive",
      })
    } finally {
      setConfigSaving(false)
    }
  }

  const handleToggleImagePullSecret = async (enabled: boolean) => {
    if (!selectedRepo) {
      return
    }

    try {
      setConfigSaving(true)
      const updatedConfig = await api.updateDeploymentImagePullSecretEnabled(
        selectedRepo.owner,
        selectedRepo.repo,
        enabled
      )
      setDeploymentConfigs((current) => ({
        ...current,
        [selectedRepo.full_name]: updatedConfig,
      }))
      toast({
        title: enabled ? "Image pull secret enabled" : "Image pull secret disabled",
        description: "The setting applies on the next deployment.",
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to save the image pull secret setting."
      toast({
        title: "Save failed",
        description: message,
        variant: "destructive",
      })
    } finally {
      setConfigSaving(false)
    }
  }

  const handleSaveRuntimeEnvFrom = async () => {
    if (!selectedRepo) {
      return
    }

    try {
      setConfigSaving(true)
      const envFromConfigMaps = parseEnvFromNames(envFromConfigMapsInput)
      const envFromSecrets = parseEnvFromNames(envFromSecretsInput)
      const updatedConfig = await api.updateDeploymentRuntimeEnvFrom(
        selectedRepo.owner,
        selectedRepo.repo,
        envFromConfigMaps,
        envFromSecrets
      )
      setDeploymentConfigs((current) => ({
        ...current,
        [selectedRepo.full_name]: updatedConfig,
      }))
      toast({
        title: "Runtime envFrom saved",
        description: "ConfigMap and Secret names will be injected on the next deployment.",
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to save runtime envFrom settings."
      toast({
        title: "Save failed",
        description: message,
        variant: "destructive",
      })
    } finally {
      setConfigSaving(false)
    }
  }

  // 사용자 인증 상태 확인
  if (authLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary"></div>
        <span className="ml-4 text-lg">인증 상태 확인 중...</span>
      </div>
    )
  }

  if (!user) {
    return (
      <div className="space-y-4">
        <Card>
          <CardHeader>
            <CardTitle>배포 상태 모니터링</CardTitle>
            <CardDescription>
              배포 상태를 확인하려면 먼저 로그인해주세요.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="text-center py-8">
              <Server className="h-16 w-16 mx-auto mb-4 text-muted-foreground" />
              <p className="text-muted-foreground mb-4">
                배포 상태를 모니터링하려면 로그인이 필요합니다.
              </p>
                     <Button onClick={() => {
                       // Header의 로그인 버튼과 동일하게 OAuth 로그인 모달 열기
                       const event = new CustomEvent('openLoginModal')
                       window.dispatchEvent(event)
                     }}>
                       로그인
                     </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Deployments</h2>
          <p className="text-muted-foreground">
            Kubernetes workload operations by repository
          </p>
        </div>
        <Button onClick={fetchRepositories} disabled={loading}>
          <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />
          Refresh
        </Button>
      </div>

      {loadError && (
        <Card>
          <CardContent className="flex items-center gap-2 py-4 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4" />
            Failed to load repositories: {loadError}
          </CardContent>
        </Card>
      )}

      {loading && repositories.length === 0 ? (
        <Card>
          <CardContent className="flex items-center justify-center py-12">
            <RefreshCw className="h-8 w-8 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      ) : repositories.length === 0 ? (
        !loadError && (
          <Card>
            <CardContent className="flex flex-col items-center justify-center py-12">
              <Server className="h-12 w-12 text-muted-foreground mb-4" />
              <p className="text-muted-foreground">No repositories connected</p>
            </CardContent>
          </Card>
        )
      ) : (
        <div className="space-y-4">
          {repositories.map((repo) => {
            const deployment = repo.latest_deployment
            const config = deploymentConfigs[repo.full_name]
            const serviceUrl = config?.domain_url ? `https://${config.domain_url}` : null
            return (
              <Card key={repo.full_name} className="overflow-hidden">
                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <CardTitle className="text-base flex items-center gap-2">
                        <Github className="h-4 w-4" />
                        {repo.full_name}
                      </CardTitle>
                      {deployment && (
                        <CardDescription className="flex items-center gap-1 mt-1">
                          <GitBranch className="h-3 w-3" />
                          {deployment.branch_name}
                        </CardDescription>
                      )}
                    </div>
                    {getStatusBadge(deployment?.status)}
                  </div>
                </CardHeader>
                <CardContent className="space-y-4">
                  {serviceUrl && (
                    <a
                      href={serviceUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-2 p-3 border-l-4 border-blue-500 rounded-md bg-muted/30 text-sm font-semibold text-blue-600 dark:text-blue-400 hover:underline"
                    >
                      <Server className="h-4 w-4 shrink-0" />
                      <span className="truncate">{serviceUrl}</span>
                      <ExternalLink className="h-3 w-3 shrink-0" />
                    </a>
                  )}

                  {configErrors[repo.full_name] && (
                    <p className="flex items-center gap-2 text-sm text-destructive">
                      <AlertTriangle className="h-4 w-4" />
                      Failed to load deployment config. Editing is disabled until it loads.
                    </p>
                  )}

                  {deployment ? (
                    <div className="grid grid-cols-2 gap-4 p-3 bg-muted/30 rounded-lg">
                      <div className="space-y-3">
                        <div>
                          <p className="text-xs text-muted-foreground mb-1">Image</p>
                          <p className="font-mono text-sm truncate">{deployment.image_uri ?? "-"}</p>
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground mb-1">Commit</p>
                          <p className="font-mono text-sm">{deployment.commit_hash.slice(0, 7)}</p>
                        </div>
                      </div>
                      <div className="space-y-3">
                        <div>
                          <p className="text-xs text-muted-foreground mb-1">Started</p>
                          <p className="text-sm font-medium">{formatTime(deployment.started_at ?? deployment.created_at)}</p>
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground mb-1">Duration</p>
                          <p className="text-sm font-medium">
                            {formatDuration(deploymentDurationSeconds(deployment.started_at, deployment.finished_at))}
                          </p>
                        </div>
                      </div>
                      {deployment.fail_reason && (
                        <p className="col-span-2 text-sm text-destructive break-words">{deployment.fail_reason}</p>
                      )}
                    </div>
                  ) : (
                    <div className="text-center py-6 text-muted-foreground">
                      <Server className="h-10 w-10 mx-auto mb-2 opacity-50" />
                      <p className="text-sm">No deployments yet</p>
                    </div>
                  )}

                  <div className="grid grid-cols-2 gap-2 lg:grid-cols-5">
                    <Button variant="outline" className="w-full" onClick={() => handleViewDetails(repo)}>
                      <Eye className="mr-2 h-4 w-4" />
                      Config
                    </Button>
                    <Button variant="outline" className="w-full" disabled={!deployment} onClick={() => handleOpenScale(repo)}>
                      <Scale className="mr-2 h-4 w-4" />
                      Scale
                    </Button>
                    <Button variant="outline" className="w-full" disabled={!deployment} onClick={() => handleOpenRollback(repo)}>
                      <RotateCcw className="mr-2 h-4 w-4" />
                      Rollback
                    </Button>
                    <Button variant="outline" className="w-full" disabled={!deployment} onClick={() => handleOpenRestart(repo)}>
                      <Play className="mr-2 h-4 w-4" />
                      Restart
                    </Button>
                    <Button variant="outline" className="w-full" disabled={!deployment} onClick={() => handleOpenLogs(repo)}>
                      <Terminal className="mr-2 h-4 w-4" />
                      Logs
                    </Button>
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}

      {/* Details Dialog */}
      <Dialog open={detailsOpen} onOpenChange={setDetailsOpen}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Github className="h-5 w-5" />
              {selectedRepo?.full_name}
            </DialogTitle>
            <DialogDescription>
              Latest deployment record and deployment configuration
            </DialogDescription>
          </DialogHeader>

          {selectedRepo && (
            <Tabs value={detailsTab} onValueChange={setDetailsTab} className="w-full">
              <TabsList className="grid w-full grid-cols-2">
                <TabsTrigger value="status">Latest Deployment</TabsTrigger>
                <TabsTrigger value="config">Configuration</TabsTrigger>
              </TabsList>

              <TabsContent value="status" className="space-y-4">
                {selectedRepo.latest_deployment ? (
                  <Card>
                    <CardContent className="grid grid-cols-1 gap-4 pt-6 sm:grid-cols-2">
                      <div>
                        <p className="text-sm text-muted-foreground">Status</p>
                        <div className="mt-1">{getStatusBadge(selectedRepo.latest_deployment.status)}</div>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Deployment ID</p>
                        <p className="mt-1 font-mono text-sm">{selectedRepo.latest_deployment.id}</p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Branch</p>
                        <p className="mt-1 text-sm">{selectedRepo.latest_deployment.branch_name}</p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Commit</p>
                        <p className="mt-1 font-mono text-sm break-all">{selectedRepo.latest_deployment.commit_hash}</p>
                      </div>
                      <div className="sm:col-span-2">
                        <p className="text-sm text-muted-foreground">Image</p>
                        <p className="mt-1 font-mono text-sm break-all">{selectedRepo.latest_deployment.image_uri ?? "-"}</p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Started</p>
                        <p className="mt-1 text-sm">{formatTime(selectedRepo.latest_deployment.started_at)}</p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Duration</p>
                        <p className="mt-1 text-sm">
                          {formatDuration(deploymentDurationSeconds(
                            selectedRepo.latest_deployment.started_at,
                            selectedRepo.latest_deployment.finished_at
                          ))}
                        </p>
                      </div>
                      {selectedRepo.latest_deployment.fail_reason && (
                        <div className="sm:col-span-2">
                          <p className="text-sm text-muted-foreground">Failure reason</p>
                          <p className="mt-1 text-sm text-destructive break-words">{selectedRepo.latest_deployment.fail_reason}</p>
                        </div>
                      )}
                    </CardContent>
                  </Card>
                ) : (
                  <div className="text-center py-8 text-muted-foreground">No deployments yet</div>
                )}
              </TabsContent>

              <TabsContent value="config" className="space-y-4">
                {configErrors[selectedRepo.full_name] || !deploymentConfigs[selectedRepo.full_name] ? (
                  <div className="flex items-center gap-2 py-8 text-sm text-destructive">
                    <AlertTriangle className="h-4 w-4" />
                    Failed to load deployment config. Refresh and try again; nothing is saved until it loads.
                  </div>
                ) : (
                  <>
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Build, image &amp; service</CardTitle>
                    <CardDescription>
                      How the image is built or chosen and how the app is exposed. Applies on the next deployment.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <div className="space-y-1">
                        <Label htmlFor="build-strategy">Build strategy</Label>
                        <Select
                          value={buildServiceInput.build_strategy}
                          onValueChange={(value) => setBuildServiceInput((current) => ({ ...current, build_strategy: value }))}
                        >
                          <SelectTrigger id="build-strategy" className="w-full">
                            <SelectValue placeholder="Select" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="KANIKO">KANIKO</SelectItem>
                            <SelectItem value="GITHUB_ACTIONS_GHCR">GITHUB_ACTIONS_GHCR</SelectItem>
                            <SelectItem value="PREBUILT_IMAGE">PREBUILT_IMAGE</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-1">
                        <Label htmlFor="service-type">Service type</Label>
                        <Select
                          value={buildServiceInput.service_type}
                          onValueChange={(value) => setBuildServiceInput((current) => ({ ...current, service_type: value }))}
                        >
                          <SelectTrigger id="service-type" className="w-full">
                            <SelectValue placeholder="Select" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="CLUSTER_IP">CLUSTER_IP</SelectItem>
                            <SelectItem value="NODE_PORT">NODE_PORT</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-1 sm:col-span-2">
                        <Label htmlFor="image-uri-template">Image URI template</Label>
                        <Input
                          id="image-uri-template"
                          value={buildServiceInput.image_uri_template}
                          onChange={(event) => setBuildServiceInput((current) => ({ ...current, image_uri_template: event.target.value }))}
                          placeholder="ghcr.io/{owner}/{repoName}:sha-{commitHash}"
                          className="font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-1">
                        <Label htmlFor="image-pull-secret-name">Image pull secret name</Label>
                        <Input
                          id="image-pull-secret-name"
                          value={buildServiceInput.image_pull_secret_name}
                          onChange={(event) => setBuildServiceInput((current) => ({ ...current, image_pull_secret_name: event.target.value }))}
                          placeholder="server default"
                          className="font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-1">
                        <Label htmlFor="node-port">NodePort</Label>
                        <Input
                          id="node-port"
                          value={buildServiceInput.node_port}
                          onChange={(event) => setBuildServiceInput((current) => ({ ...current, node_port: event.target.value }))}
                          placeholder="30000-32767"
                          disabled={buildServiceInput.service_type !== "NODE_PORT"}
                          className="font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-1">
                        <Label htmlFor="container-port">Container port</Label>
                        <Input
                          id="container-port"
                          value={buildServiceInput.container_port}
                          onChange={(event) => setBuildServiceInput((current) => ({ ...current, container_port: event.target.value }))}
                          className="font-mono text-sm"
                        />
                      </div>
                      <div className="space-y-1">
                        <Label htmlFor="domain-url">Domain</Label>
                        <Input
                          id="domain-url"
                          value={buildServiceInput.domain_url}
                          onChange={(event) => setBuildServiceInput((current) => ({ ...current, domain_url: event.target.value }))}
                          className="font-mono text-sm"
                        />
                      </div>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      KANIKO builds from source. GITHUB_ACTIONS_GHCR and PREBUILT_IMAGE deploy an image built elsewhere
                      using the image URI template. The server rejects pull secret names the operator has not allowed
                      for this repository.
                    </p>
                    <Button onClick={handleSaveBuildAndService} disabled={configSaving}>
                      <Save className="mr-2 h-4 w-4" />
                      {configSaving ? "Saving..." : "Save build & service"}
                    </Button>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Runtime envFrom</CardTitle>
                    <CardDescription>
                      Store Kubernetes ConfigMap and Secret names for deployment-time environment sources.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="env-from-configmaps">ConfigMap names</Label>
                      <Textarea
                        id="env-from-configmaps"
                        value={envFromConfigMapsInput}
                        onChange={(event) => setEnvFromConfigMapsInput(event.target.value)}
                        placeholder="smart-sousvide-runtime-config"
                        className="min-h-20 font-mono text-sm"
                      />
                      <p className="text-xs text-muted-foreground">
                        Separate multiple names with commas or new lines.
                      </p>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="env-from-secrets">Secret names</Label>
                      <Textarea
                        id="env-from-secrets"
                        value={envFromSecretsInput}
                        onChange={(event) => setEnvFromSecretsInput(event.target.value)}
                        placeholder="smart-sousvide-app-env"
                        className="min-h-20 font-mono text-sm"
                      />
                      <p className="text-xs text-muted-foreground">
                        Only Secret names are stored. Secret values are never shown or submitted here.
                      </p>
                    </div>
                    <Button onClick={handleSaveRuntimeEnvFrom} disabled={configSaving}>
                      <Save className="mr-2 h-4 w-4" />
                      {configSaving ? "Saving..." : "Save runtime envFrom"}
                    </Button>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Image pull secret</CardTitle>
                    <CardDescription>
                      Turn this off for public images. Deployments then run without imagePullSecrets, so an
                      expired registry credential cannot block them.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="flex items-center justify-between gap-4">
                    <div className="space-y-1">
                      <Label htmlFor="image-pull-secret-enabled">Use image pull secret</Label>
                      <p className="font-mono text-xs text-muted-foreground">
                        {selectedRepo && deploymentConfigs[selectedRepo.full_name]?.image_pull_secret_name
                          ? deploymentConfigs[selectedRepo.full_name]?.image_pull_secret_name
                          : "server default"}
                      </p>
                    </div>
                    <Switch
                      id="image-pull-secret-enabled"
                      checked={selectedRepo ? deploymentConfigs[selectedRepo.full_name]?.image_pull_secret_enabled !== false : true}
                      onCheckedChange={handleToggleImagePullSecret}
                      disabled={configSaving || !selectedRepo || !deploymentConfigs[selectedRepo.full_name]}
                    />
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Health probe &amp; resources</CardTitle>
                    <CardDescription>
                      An HTTP readiness probe keeps a deployment from succeeding until the app answers on this path.
                      Leave the path empty for no probe and resource fields empty for no requests/limits.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {[PROBE_FIELDS, RESOURCE_FIELDS].map((fields, index) => (
                      <div key={index} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        {fields.map(([key, label, placeholder]) => (
                          <div key={key} className="space-y-1">
                            <Label htmlFor={`runtime-check-${key}`}>{label}</Label>
                            <Input
                              id={`runtime-check-${key}`}
                              value={runtimeCheckInput[key]}
                              onChange={(event) =>
                                setRuntimeCheckInput((current) => ({ ...current, [key]: event.target.value }))
                              }
                              placeholder={placeholder}
                              className="font-mono text-sm"
                            />
                          </div>
                        ))}
                      </div>
                    ))}
                    <p className="text-xs text-muted-foreground">
                      The startup probe uses the same path; it allows period × startup failure threshold seconds to boot.
                    </p>
                    <Button onClick={handleSaveProbeAndResources} disabled={configSaving}>
                      <Save className="mr-2 h-4 w-4" />
                      {configSaving ? "Saving..." : "Save probe & resources"}
                    </Button>
                  </CardContent>
                </Card>
                  </>
                )}
              </TabsContent>
            </Tabs>
          )}
        </DialogContent>
      </Dialog>

      {/* Rollback Dialog */}
      {actionRepo && (
        <RollbackDialog
          open={rollbackDialogOpen}
          onOpenChange={setRollbackDialogOpen}
          owner={actionRepo.owner}
          repo={actionRepo.repo}
          onRollbackSuccess={handleActionSuccess}
        />
      )}

      {/* Scale Dialog */}
      {actionRepo && (
        <ScaleDialog
          open={scaleDialogOpen}
          onOpenChange={setScaleDialogOpen}
          owner={actionRepo.owner}
          repo={actionRepo.repo}
          currentReplicas={deploymentConfigs[actionRepo.full_name]?.replica_count}
          onScaleSuccess={handleActionSuccess}
        />
      )}

      {/* Restart Dialog */}
      {actionRepo && (
        <RestartDialog
          open={restartDialogOpen}
          onOpenChange={setRestartDialogOpen}
          owner={actionRepo.owner}
          repo={actionRepo.repo}
          currentCommitSha={actionRepo.latest_deployment?.commit_hash}
          currentImage={actionRepo.latest_deployment?.image_uri ?? undefined}
          onRestartSuccess={handleActionSuccess}
        />
      )}
      {/* Logs Dialog */}
      {actionRepo && (
        <DeploymentLogsDialog
          open={logsDialogOpen}
          onOpenChange={setLogsDialogOpen}
          namespace="default"
          appName={`${actionRepo.owner}-${actionRepo.repo}`.toLowerCase()}
        />
      )}
    </div>
  )
}
