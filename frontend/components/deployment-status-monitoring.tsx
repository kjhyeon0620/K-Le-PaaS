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
import { api, type DeploymentConfigResponse } from "@/lib/api"
import { useAuth } from "@/contexts/auth-context"
import { useToast } from "@/hooks/use-toast"
import { RollbackDialog } from "@/components/rollback-dialog"
import { ScaleDialog } from "@/components/scale-dialog"
import { RestartDialog } from "@/components/restart-dialog"
import { DeploymentLogsDialog } from "@/components/deployment-logs-dialog"
import { formatDuration } from "@/lib/utils"
import { formatImageDisplay } from "@/lib/utils/image-formatter"

interface RepositoryWorkload {
  owner: string
  repo: string
  full_name: string
  branch: string
  latest_deployment: {
    id: number
    status: "running" | "success" | "failed"
    image: {
      name: string
      tag: string
      url: string
    }
    commit: {
      sha: string
      short_sha: string
      message: string
      author: string
      url: string
    }
    cluster: {
      namespace: string
      replicas: {
        desired: number
        ready: number
      }
      resources: {
        cpu: number
        memory: number
      }
    }
    timing: {
      started_at: string
      completed_at: string
      total_duration: number
    }
    service_url?: string  // 배포된 서비스 URL
  } | null
  auto_deploy_enabled: boolean
}

interface DeploymentStatusMonitoringProps {
  onNavigateToMonitoring?: () => void
  onNavigateToPipelines?: () => void
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
  onNavigateToPipelines
}: DeploymentStatusMonitoringProps = {}) {
  const [repositories, setRepositories] = useState<RepositoryWorkload[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedRepo, setSelectedRepo] = useState<RepositoryWorkload | null>(null)
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [deploymentConfigs, setDeploymentConfigs] = useState<Record<string, DeploymentConfigResponse>>({})
  const [envFromConfigMapsInput, setEnvFromConfigMapsInput] = useState("")
  const [envFromSecretsInput, setEnvFromSecretsInput] = useState("")
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

      // Fetch deployment configs for each repository
      const configs: Record<string, DeploymentConfigResponse> = {}
      await Promise.all(
        response.repositories.map(async (repo: RepositoryWorkload) => {
          try {
            const config = await api.getDeploymentConfig(repo.owner, repo.repo)
            configs[repo.full_name] = config
          } catch (error) {
            console.error(`Failed to fetch config for ${repo.full_name}:`, error)
            // Use default replica count if config fetch fails
            configs[repo.full_name] = {
              owner: repo.owner,
              repo: repo.repo,
              min_replicas: 1,
              max_replicas: 1,
              replica_count: 1,
              env_vars: {},
              env_from_config_maps: [],
              env_from_secrets: [],
              container_port: 8080,
              domain_url: null,
            }
          }
        })
      )
      setDeploymentConfigs(configs)
    } catch (error) {
      console.error("Failed to fetch repositories:", error)
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
  }, [deploymentConfigs, detailsOpen, selectedRepo])

  const getStatusBadge = (status: string | undefined) => {
    if (!status) {
      return <Badge variant="outline">No Deployments</Badge>
    }

    switch (status) {
      case "success":
        return (
          <Badge className="bg-green-500">
            <CheckCircle className="mr-1 h-3 w-3" />
            Running
          </Badge>
        )
      case "running":
        return (
          <Badge className="bg-blue-500">
            <Clock className="mr-1 h-3 w-3" />
            Deploying
          </Badge>
        )
      case "failed":
        return (
          <Badge variant="destructive">
            <AlertTriangle className="mr-1 h-3 w-3" />
            Failed
          </Badge>
        )
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  const formatTime = (isoString: string) => {
    // 한국 시간대 설정
    const koreaTimeZone = 'Asia/Seoul'
    
    // 현재 한국 시간
    const now = new Date()
    const koreaNow = new Date(now.toLocaleString("en-US", { timeZone: koreaTimeZone }))
    
    // 입력된 날짜를 한국 시간으로 변환
    const inputDate = new Date(isoString)
    const koreaInputDate = new Date(inputDate.toLocaleString("en-US", { timeZone: koreaTimeZone }))
    
    // 시간 차이 계산 (밀리초)
    const diffMs = koreaNow.getTime() - koreaInputDate.getTime()
    const diffMins = Math.floor(diffMs / 60000)

    if (diffMins < 1) return "Just now"
    if (diffMins < 60) return `${diffMins}m ago`
    const diffHours = Math.floor(diffMins / 60)
    if (diffHours < 24) return `${diffHours}h ago`
    const diffDays = Math.floor(diffHours / 24)
    return `${diffDays}d ago`
  }

  const handleViewDetails = (repo: RepositoryWorkload) => {
    setSelectedRepo(repo)
    setDetailsOpen(true)
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

      {loading && repositories.length === 0 ? (
        <Card>
          <CardContent className="flex items-center justify-center py-12">
            <RefreshCw className="h-8 w-8 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      ) : repositories.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12">
            <Server className="h-12 w-12 text-muted-foreground mb-4" />
            <p className="text-muted-foreground">No repositories connected</p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {repositories.map((repo) => (
            <Card key={repo.full_name} className="overflow-hidden">
              <CardHeader className="pb-3">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <CardTitle className="text-base flex items-center gap-2">
                      <Github className="h-4 w-4" />
                      {repo.full_name}
                    </CardTitle>
                    <CardDescription className="flex items-center gap-1 mt-1">
                      <GitBranch className="h-3 w-3" />
                      {repo.branch}
                    </CardDescription>
                  </div>
                  {getStatusBadge(repo.latest_deployment?.status)}
                </div>
              </CardHeader>
              <CardContent className="space-y-4">
                {repo.latest_deployment ? (
                  <>
                    {/* Domain URL - Prominent Section */}
                    <div className="flex items-center justify-between p-3 bg-gradient-to-r from-blue-50 to-transparent dark:from-blue-950/30 dark:to-transparent border-l-4 border-blue-500 rounded-md group">
                      <a
                        href={repo.latest_deployment.service_url || `https://${repo.repo}.klepaas.app`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="flex items-center gap-2 flex-1 min-w-0"
                      >
                        <Server className="h-4 w-4 text-blue-600 dark:text-blue-400 shrink-0" />
                        <span className="text-sm font-semibold text-blue-600 dark:text-blue-400 truncate group-hover:underline">
                          {repo.latest_deployment.service_url || `https://${repo.repo}.klepaas.app`}
                        </span>
                        <ExternalLink className="h-3 w-3 text-blue-600 dark:text-blue-400 opacity-50 group-hover:opacity-100 transition-opacity shrink-0" />
                      </a>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="shrink-0 h-8 px-3 text-blue-600 hover:text-blue-700 hover:bg-blue-100 dark:text-blue-400 dark:hover:bg-blue-900/30"
                        onClick={async (e) => {
                          e.stopPropagation()
                          const url = repo.latest_deployment?.service_url || `https://${repo.repo}.klepaas.app`
                          try {
                            await navigator.clipboard.writeText(url)
                          } catch (error) {
                            console.error('Failed to copy to clipboard:', error)
                            // Fallback for older browsers or non-HTTPS
                            const textArea = document.createElement('textarea')
                            textArea.value = url
                            document.body.appendChild(textArea)
                            textArea.select()
                            document.execCommand('copy')
                            document.body.removeChild(textArea)
                          }
                        }}
                      >
                        Copy
                      </Button>
                    </div>

                    {/* Image Info */}
                    <div>
                      <p className="text-xs text-muted-foreground mb-1">Image</p>
                      <p className="font-mono text-sm truncate">
                        {formatImageDisplay(repo.owner, repo.repo, repo.latest_deployment.image.tag)}
                      </p>
                    </div>

                    {/* Deployment Info */}
                    <div className="grid grid-cols-2 gap-4 p-3 bg-muted/30 rounded-lg">
                      <div className="space-y-3">
                        <div>
                          <div className="flex items-center gap-1 text-xs text-muted-foreground mb-1">
                            <Server className="h-3 w-3" />
                            Replicas
                          </div>
                          <p className="text-sm font-semibold">
                            {repo.latest_deployment.cluster.replicas.ready}/
                            {deploymentConfigs[repo.full_name]?.replica_count ?? repo.latest_deployment.cluster.replicas.desired}
                            <span className="text-xs text-muted-foreground ml-2">
                              {repo.latest_deployment.cluster.replicas.ready ===
                              (deploymentConfigs[repo.full_name]?.replica_count ?? repo.latest_deployment.cluster.replicas.desired)
                                ? "• All Ready"
                                : "• Scaling"}
                            </span>
                          </p>
                        </div>
                        <div>
                          <div className="flex items-center gap-1 text-xs text-muted-foreground mb-1">
                            <GitBranch className="h-3 w-3" />
                            Last Commit
                          </div>
                          <p className="text-sm font-medium truncate">
                            {repo.latest_deployment.commit.message || "Initial deployment"}
                          </p>
                        </div>
                      </div>

                      <div className="space-y-3">
                        <div>
                          <div className="flex items-center gap-1 text-xs text-muted-foreground mb-1">
                            <Github className="h-3 w-3" />
                            Author
                          </div>
                          <p className="text-sm font-medium">
                            {repo.latest_deployment.commit.author || "System"} •{" "}
                            {formatTime(repo.latest_deployment.timing.started_at)}
                          </p>
                        </div>
                        <div>
                          <div className="flex items-center gap-1 text-xs text-muted-foreground mb-1">
                            <Clock className="h-3 w-3" />
                            Deploy Time
                          </div>
                          <p className="text-sm font-medium">
                            {formatDuration(repo.latest_deployment.timing.total_duration)}
                          </p>
                        </div>
                      </div>
                    </div>

                    {/* Actions */}
                    <div className="grid grid-cols-2 gap-2 lg:grid-cols-5">
                      <Button
                        size="default"
                        variant="outline"
                        className="w-full"
                        onClick={() => handleViewDetails(repo)}
                      >
                        <Eye className="mr-2 h-4 w-4" />
                        Config
                      </Button>
                      <Button
                        size="default"
                        variant="outline"
                        className="w-full"
                        onClick={() => handleOpenScale(repo)}
                      >
                        <Scale className="mr-2 h-4 w-4" />
                        Scale
                      </Button>
                      <Button
                        size="default"
                        variant="outline"
                        className="w-full"
                        onClick={() => handleOpenRollback(repo)}
                      >
                        <RotateCcw className="mr-2 h-4 w-4" />
                        Rollback
                      </Button>
                      <Button
                        size="default"
                        variant="outline"
                        className="w-full"
                        onClick={() => handleOpenRestart(repo)}
                      >
                        <Play className="mr-2 h-4 w-4" />
                        Restart
                      </Button>
                      <Button
                        size="default"
                        variant="outline"
                        className="w-full"
                        onClick={() => handleOpenLogs(repo)}
                      >
                        <Terminal className="mr-2 h-4 w-4" />
                        Logs
                      </Button>
                    </div>
                  </>
                ) : (
                  <div className="text-center py-8 text-muted-foreground">
                    <Server className="h-12 w-12 mx-auto mb-2 opacity-50" />
                    <p className="text-sm">No deployments yet</p>
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
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
              Deployment configuration and status
            </DialogDescription>
          </DialogHeader>

          {selectedRepo?.latest_deployment && (
            <Tabs defaultValue="status" className="w-full">
              <TabsList className="grid w-full grid-cols-2">
                <TabsTrigger value="status">Quick Status</TabsTrigger>
                <TabsTrigger value="config">Configuration</TabsTrigger>
              </TabsList>

              <TabsContent value="status" className="space-y-4">
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Deployment Status</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <p className="text-sm text-muted-foreground">Status</p>
                        <div className="mt-1">
                          {getStatusBadge(selectedRepo.latest_deployment.status)}
                        </div>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Namespace</p>
                        <p className="mt-1 font-medium">
                          {selectedRepo.latest_deployment.cluster.namespace}
                        </p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Replicas</p>
                        <p className="mt-1 font-medium">
                          {selectedRepo.latest_deployment.cluster.replicas.ready} /{" "}
                          {deploymentConfigs[selectedRepo.full_name]?.replica_count ?? selectedRepo.latest_deployment.cluster.replicas.desired}
                        </p>
                      </div>
                      <div>
                        <p className="text-sm text-muted-foreground">Duration</p>
                        <p className="mt-1 font-medium">
                          {selectedRepo.latest_deployment.timing.total_duration}s
                        </p>
                      </div>
                    </div>

                    <div>
                      <p className="text-sm text-muted-foreground mb-2">Resources</p>
                      <div className="space-y-2">
                        <div>
                          <div className="flex justify-between text-sm mb-1">
                            <span>CPU Usage</span>
                            <span className="font-medium">
                              {selectedRepo.latest_deployment.cluster.resources.cpu}%
                            </span>
                          </div>
                          <Progress
                            value={selectedRepo.latest_deployment.cluster.resources.cpu}
                          />
                        </div>
                        <div>
                          <div className="flex justify-between text-sm mb-1">
                            <span>Memory Usage</span>
                            <span className="font-medium">
                              {selectedRepo.latest_deployment.cluster.resources.memory}%
                            </span>
                          </div>
                          <Progress
                            value={selectedRepo.latest_deployment.cluster.resources.memory}
                          />
                        </div>
                      </div>
                    </div>

                    <div className="flex gap-2 pt-2">
                      <Button 
                        variant="outline" 
                        className="flex-1"
                        onClick={() => {
                          setDetailsOpen(false)
                          if (onNavigateToMonitoring) {
                            onNavigateToMonitoring()
                          }
                        }}
                      >
                        <Activity className="mr-2 h-4 w-4" />
                        View Monitoring
                      </Button>
                      <Button 
                        variant="outline" 
                        className="flex-1"
                        onClick={() => {
                          setDetailsOpen(false)
                          if (onNavigateToPipelines) {
                            onNavigateToPipelines()
                          }
                        }}
                      >
                        <Github className="mr-2 h-4 w-4" />
                        View CI/CD History
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              </TabsContent>

              <TabsContent value="config" className="space-y-4">
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Image Configuration</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <div>
                      <p className="text-sm text-muted-foreground">Image</p>
                      <p className="mt-1 font-mono text-sm">
                        {formatImageDisplay(selectedRepo.owner, selectedRepo.repo, selectedRepo.latest_deployment.image.tag)}
                      </p>
                    </div>
                    <div>
                      <p className="text-sm text-muted-foreground">Image URL</p>
                      <a
                        href={selectedRepo.latest_deployment.image.url || "#"}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="mt-1 font-mono text-sm text-blue-500 hover:underline flex items-center gap-1"
                      >
                        {selectedRepo.latest_deployment.image.url || "N/A"}
                        <ExternalLink className="h-3 w-3" />
                      </a>
                    </div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Commit Information</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <div>
                      <p className="text-sm text-muted-foreground">Commit SHA</p>
                      <p className="mt-1 font-mono text-sm">
                        {selectedRepo.latest_deployment.commit.sha}
                      </p>
                    </div>
                    <div>
                      <p className="text-sm text-muted-foreground">Message</p>
                      <p className="mt-1 text-sm">
                        {selectedRepo.latest_deployment.commit.message}
                      </p>
                    </div>
                    <div>
                      <p className="text-sm text-muted-foreground">Author</p>
                      <p className="mt-1 text-sm">
                        {selectedRepo.latest_deployment.commit.author}
                      </p>
                    </div>
                    <div>
                      <a
                        href={selectedRepo.latest_deployment.commit.url || "#"}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-sm text-blue-500 hover:underline flex items-center gap-1"
                      >
                        View on GitHub
                        <ExternalLink className="h-3 w-3" />
                      </a>
                    </div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Cluster Configuration</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <div>
                      <p className="text-sm text-muted-foreground">Namespace</p>
                      <p className="mt-1 font-mono text-sm">
                        {selectedRepo.latest_deployment.cluster.namespace}
                      </p>
                    </div>
                    <div>
                      <p className="text-sm text-muted-foreground">Auto Deploy</p>
                      <Badge variant={selectedRepo.auto_deploy_enabled ? "default" : "outline"}>
                        {selectedRepo.auto_deploy_enabled ? "Enabled" : "Disabled"}
                      </Badge>
                    </div>
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
              </TabsContent>
            </Tabs>
          )}

          {!selectedRepo?.latest_deployment && (
            <div className="text-center py-8 text-muted-foreground">
              No deployment information available
            </div>
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
          currentReplicas={deploymentConfigs[actionRepo.full_name]?.replica_count ?? actionRepo.latest_deployment?.cluster.replicas.desired}
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
          currentCommitSha={actionRepo.latest_deployment?.commit.sha}
          currentImage={actionRepo.latest_deployment?.image.url}
          onRestartSuccess={handleActionSuccess}
        />
      )}
      {/* Logs Dialog */}
      {actionRepo && (
        <DeploymentLogsDialog
          open={logsDialogOpen}
          onOpenChange={setLogsDialogOpen}
          namespace={actionRepo.latest_deployment?.cluster.namespace || "default"}
          appName={`${actionRepo.owner}-${actionRepo.repo}`.toLowerCase()}
        />
      )}
    </div>
  )
}
