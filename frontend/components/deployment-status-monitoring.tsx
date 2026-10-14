"use client"

import { useState, useEffect } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Progress } from "@/components/ui/progress"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
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
  History,
} from "lucide-react"
import { api, type DeploymentConfigResponse, type DeploymentStatus, type RepositoryWorkload } from "@/lib/api"
import { useAuth } from "@/contexts/auth-context"
import { useToast } from "@/hooks/use-toast"
import { RollbackDialog } from "@/components/rollback-dialog"
import { ScaleDialog } from "@/components/scale-dialog"
import { RestartDialog } from "@/components/restart-dialog"
import { DeploymentLogsDialog } from "@/components/deployment-logs-dialog"
import { DeploymentHistory, DeploymentRequestDetail } from "@/components/deployment-request-detail"
import { DeploymentConfigForm } from "@/components/deployment-config-form"
import { formatDuration } from "@/lib/utils"
import { formatKst, formatTimeAgo, parseServerTime } from "@/lib/time"
import { formatImageDisplay } from "@/lib/utils/image-formatter"

interface DeploymentStatusMonitoringProps {
  onNavigateToMonitoring?: () => void
  onNavigateToPipelines?: () => void
  // GitHub 화면의 Configure에서 넘어올 때 열 저장소 (owner/repo)
  configureRepo?: string | null
  onConfigureRepoHandled?: () => void
}

const IN_PROGRESS_STATUSES: readonly DeploymentStatus[] = ["PENDING", "UPLOADING_SOURCE", "BUILDING", "DEPLOYING"]

function deploymentDurationSeconds(startedAt: string | null, finishedAt: string | null): number | null {
  const start = parseServerTime(startedAt)
  const end = parseServerTime(finishedAt)
  if (!start || !end) return null
  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 1000))
}


export function DeploymentStatusMonitoring({
  onNavigateToMonitoring,
  onNavigateToPipelines,
  configureRepo,
  onConfigureRepoHandled,
}: DeploymentStatusMonitoringProps = {}) {
  const [repositories, setRepositories] = useState<RepositoryWorkload[]>([])
  // 배포 목록·요청 상세 화면 (#95). URL ?repository=<id>, ?deployment=<id>로 열고 공유한다
  const [route, setRoute] = useState<{ repository: number | null; deployment: number | null }>({ repository: null, deployment: null })

  useEffect(() => {
    const readRoute = () => {
      const params = new URLSearchParams(window.location.search)
      const toId = (v: string | null) => (v && /^\d+$/.test(v) ? Number(v) : null)
      setRoute({ repository: toId(params.get("repository")), deployment: toId(params.get("deployment")) })
    }
    readRoute()
    window.addEventListener("popstate", readRoute)
    return () => window.removeEventListener("popstate", readRoute)
  }, [])

  const navigate = (next: { repository: number | null; deployment: number | null }) => {
    const url = new URL(window.location.href)
    for (const key of ["repository", "deployment"] as const) {
      if (next[key] === null) url.searchParams.delete(key)
      else url.searchParams.set(key, String(next[key]))
    }
    window.history.pushState({}, "", url.toString())
    setRoute(next)
  }
  const [loading, setLoading] = useState(true)
  const [selectedRepo, setSelectedRepo] = useState<RepositoryWorkload | null>(null)
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [deploymentConfigs, setDeploymentConfigs] = useState<Record<string, DeploymentConfigResponse>>({})
  const [configErrors, setConfigErrors] = useState<Record<string, string>>({})
  const [loadError, setLoadError] = useState<string | null>(null)
  const [detailsTab, setDetailsTab] = useState("status")

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
    if (status === "UNKNOWN") {
      return <Badge variant="outline">판정 불가</Badge>
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

  const handleViewDetails = (repo: RepositoryWorkload, tab: "status" | "config" = "status") => {
    setSelectedRepo(repo)
    setDetailsTab(tab)
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

  if (route.deployment !== null) {
    return (
      <DeploymentRequestDetail
        deploymentId={route.deployment}
        onBack={() => (route.repository !== null
          ? navigate({ repository: route.repository, deployment: null })
          : window.history.length > 1 ? window.history.back() : navigate({ repository: null, deployment: null }))}
      />
    )
  }
  if (route.repository !== null) {
    const repo = repositories.find((r) => r.id === route.repository)
    return (
      <DeploymentHistory
        repositoryId={route.repository}
        repositoryName={repo?.full_name ?? `저장소 #${route.repository}`}
        onOpen={(id) => navigate({ repository: route.repository, deployment: id })}
        onBack={() => navigate({ repository: null, deployment: null })}
      />
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
                          <p className="text-sm font-medium">{formatTimeAgo(deployment.started_at ?? deployment.created_at)}</p>
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

                  <div className="grid grid-cols-2 gap-2 lg:grid-cols-6">
                    <Button variant="outline" className="w-full" onClick={() => navigate({ repository: repo.id, deployment: null })}>
                      <History className="mr-2 h-4 w-4" />
                      History
                    </Button>
                    <Button variant="outline" className="w-full" onClick={() => handleViewDetails(repo, "config")}>
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
                        <p className="mt-1 text-sm">{formatKst(selectedRepo.latest_deployment.started_at)}</p>
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
                  // 열 때의 설정으로 시작한다. 30초 목록 갱신이 입력 중인 값을 덮어쓰지 않는다 (#106)
                  <DeploymentConfigForm
                    key={selectedRepo.full_name}
                    owner={selectedRepo.owner}
                    repo={selectedRepo.repo}
                    config={deploymentConfigs[selectedRepo.full_name]}
                    onSaved={(saved) => {
                      setDeploymentConfigs((current) => ({ ...current, [selectedRepo.full_name]: saved }))
                      toast({ title: "Deployment settings saved", description: "The settings apply on the next deployment." })
                    }}
                  />
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
          repositoryId={actionRepo.id}
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
      {actionRepo?.latest_deployment && (
        <DeploymentLogsDialog
          open={logsDialogOpen}
          onOpenChange={setLogsDialogOpen}
          deploymentId={actionRepo.latest_deployment.id}
          appName={`${actionRepo.owner}-${actionRepo.repo}`.toLowerCase()}
        />
      )}
    </div>
  )
}
