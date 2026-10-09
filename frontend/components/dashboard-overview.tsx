"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Progress } from "@/components/ui/progress"
import { Server, GitBranch, AlertTriangle, CheckCircle, Clock, Cpu, HardDrive, Github, Eye, Zap, GitPullRequest, Settings } from "lucide-react"
import { apiClient, api, type RepositoryWorkload } from "@/lib/api"
import { useAuth } from "@/contexts/auth-context"
import { formatTimeAgo } from "@/lib/utils"
import { formatImageDisplay } from "@/lib/utils/image-formatter"
import { RecentCommands } from "@/components/recent-commands"

interface ConnectedRepository {
  id: string
  name: string
  fullName: string
  branch: string
  lastSync: string
}

interface PullRequest {
  id: string
  number: number
  title: string
  author: string
  status: string
  createdAt: string
  htmlUrl: string
}

interface DashboardData {
  clusters: number
  deployments: number
  pendingDeployments: number
  activeDeployments: number
  cpuUsage: number
  memoryUsage: number
  systemHealth: Array<{
    service: string
    status: 'healthy' | 'warning' | 'error'
  }>
  connectedRepositories: ConnectedRepository[]
  pullRequests: PullRequest[]
}

interface DashboardOverviewProps {
  onNavigateToDeployments?: () => void
  onNavigateToChat?: (commandId: number) => void
  onNavigateToRepositories?: () => void
  onNavigateToPullRequests?: () => void
  onNavigateToMonitoring?: (tab?: 'nodes' | 'details' | 'alerts' | 'resources') => void
}

export function DashboardOverview({ onNavigateToDeployments, onNavigateToChat, onNavigateToRepositories, onNavigateToPullRequests, onNavigateToMonitoring }: DashboardOverviewProps) {
  const router = useRouter()
  const [data, setData] = useState<DashboardData | null>(null)
  const [repositories, setRepositories] = useState<RepositoryWorkload[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  // null = 수집된 값 없음. 0%로 표시하지 않는다.
  const [cpuUsage, setCpuUsage] = useState<number | null>(null)
  const [memoryUsage, setMemoryUsage] = useState<number | null>(null)
  const [alerts, setAlerts] = useState<Array<{ id: string; title?: string; message?: string; severity?: string; created_at?: string }>>([])

  // 사용자 인증 상태 확인
  const { user, isLoading: authLoading } = useAuth()

  useEffect(() => {
    const fetchDashboardData = async () => {
      try {
        // Fetch dashboard stats
        const dashboardData = await apiClient.getDashboardData() as DashboardData
        setData(dashboardData)

        // Fetch active repositories
        try {
          const repoResponse = await api.getRepositoriesLatestDeployments()
          const repos = repoResponse.repositories?.slice(0, 4) || []
          setRepositories(repos)
        } catch (repoError) {
          console.error('Failed to fetch repositories:', repoError)
          setRepositories([])
        }

        // Fetch CPU and Memory usage metrics
        try {
          const cpuResult = await api.getNKSCpuUsage() as any
          if (cpuResult.status === 'success' && cpuResult.value) {
            setCpuUsage(cpuResult.value)
          }
        } catch (error) {
          console.error('Failed to fetch CPU metrics:', error)
        }

        try {
          const memoryResult = await api.getNKSMemoryUsage() as any
          if (memoryResult.status === 'success' && memoryResult.value) {
            setMemoryUsage(memoryResult.value)
          }
        } catch (error) {
          console.error('Failed to fetch memory metrics:', error)
        }

        // Fetch latest alerts (limit to 3 in render)
        try {
          const alertList = await apiClient.getAlerts('nks-cluster') as any[]
          setAlerts(Array.isArray(alertList) ? alertList.slice(0, 3) : [])
        } catch (error) {
          console.error('Failed to fetch alerts:', error)
          setAlerts([])
        }
      } catch (error) {
        console.error('Failed to fetch dashboard data:', error)
        setLoadError(true)
      } finally {
        setLoading(false)
      }
    }

    fetchDashboardData()
  }, [])

  if (loading) {
    return (
      <div className="space-y-6">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {[...Array(4)].map((_, i) => (
            <Card key={i}>
              <CardHeader className="animate-pulse">
                <div className="h-4 bg-gray-200 rounded w-1/2"></div>
              </CardHeader>
              <CardContent className="animate-pulse">
                <div className="h-8 bg-gray-200 rounded w-1/3 mb-2"></div>
                <div className="h-3 bg-gray-200 rounded w-2/3"></div>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    )
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
      <div className="space-y-6">
        <Card className="lg:order-4 lg:col-start-4">
          <CardHeader>
            <CardTitle>대시보드</CardTitle>
            <CardDescription>
              대시보드를 확인하려면 먼저 로그인해주세요.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="text-center py-8">
              <Server className="h-16 w-16 mx-auto mb-4 text-muted-foreground" />
              <p className="text-muted-foreground mb-4">
                대시보드를 확인하려면 로그인이 필요합니다.
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

  if (loadError) {
    return (
      <Card>
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          대시보드 데이터를 불러오지 못했습니다. 백엔드 연결 상태를 확인한 뒤 다시 시도해 주세요.
        </CardContent>
      </Card>
    )
  }

  if (!data) return null
  return (
    <div className="space-y-6">
      {/* Status Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <Card className="lg:order-1">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Connected Repositories</CardTitle>
            <Github className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {data.connectedRepositories && data.connectedRepositories.length > 0 ? (
                data.connectedRepositories.map((repo) => (
                  <div key={repo.id} className="flex items-center justify-between">
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{repo.fullName}</p>
                      <p className="text-xs text-muted-foreground">
                        {repo.branch} • {formatTimeAgo(repo.lastSync)}
                      </p>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-2">
                  <p className="text-sm text-muted-foreground">No repositories connected</p>
                </div>
              )}
            </div>
            <div className="mt-3 pt-3 border-t">
              <Button 
                variant="ghost" 
                size="sm" 
                className="w-full text-xs"
                onClick={() => {
                  if (onNavigateToRepositories) {
                    onNavigateToRepositories()
                  } else {
                    router.push('/github')
                  }
                }}
              >
                View All
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* Alerts Card */}
        <Card className="lg:order-4">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Alerts</CardTitle>
            <AlertTriangle className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {alerts && alerts.length > 0 ? (
                alerts.slice(0, 3).map((a) => (
                  <div key={`dash-alert-${a.id}`} className="flex items-center justify-between rounded-md border px-2 py-2">
                    <div className="min-w-0 mr-2">
                      <p className="text-sm font-medium truncate">{a.title || a.message || 'Alert'}</p>
                    </div>
                    <Badge variant={a.severity === 'critical' ? 'destructive' : 'secondary'} className="text-[10px]">
                      {a.severity || 'info'}
                    </Badge>
                  </div>
                ))
              ) : (
                <div className="text-center py-6 text-muted-foreground text-sm">No active alerts</div>
              )}
            </div>
            <div className="mt-3 pt-3 border-t">
              <Button
                variant="ghost"
                size="sm"
                className="w-full text-xs"
                onClick={() => {
                  if (onNavigateToMonitoring) {
                    onNavigateToMonitoring('alerts')
                  }
                  try {
                    const url = new URL(window.location.href)
                    url.searchParams.set('view', 'monitoring')
                    url.searchParams.set('tab', 'alerts')
                    window.history.replaceState({}, '', url.toString())
                  } catch {}
                  if (!onNavigateToMonitoring) {
                    router.push('/?view=monitoring&tab=alerts')
                  }
                }}
              >
                View All
              </Button>
            </div>
          </CardContent>
        </Card>
        <Card className="lg:order-2">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Pull Requests</CardTitle>
            <GitPullRequest className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {data.pullRequests && data.pullRequests.length > 0 ? (
                data.pullRequests.map((pr) => (
                  <div key={pr.id} className="flex items-center justify-between">
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{pr.title}</p>
                      <p className="text-xs text-muted-foreground">
                        by @{pr.author} • {formatTimeAgo(pr.createdAt)}
                      </p>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-2">
                  <p className="text-sm text-muted-foreground">No pull requests</p>
                </div>
              )}
            </div>
            <div className="mt-3 pt-3 border-t">
              <Button 
                variant="ghost" 
                size="sm" 
                className="w-full text-xs"
                onClick={() => {
                  if (onNavigateToPullRequests) {
                    onNavigateToPullRequests()
                  } else {
                    router.push('/github')
                  }
                }}
              >
                View All
              </Button>
            </div>
          </CardContent>
        </Card>

        <Card className="lg:order-3">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">System Utilization</CardTitle>
            <Settings className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {/* CPU */}
            <div className="mb-4">
              <div className="flex items-center justify-between">
                <div className="text-sm font-medium">CPU Usage</div>
                <div className="text-sm text-muted-foreground">{cpuUsage === null ? "미수집" : `${cpuUsage.toFixed(2)}%`}</div>
              </div>
              <Progress value={cpuUsage ?? 0} className="mt-2" />
            </div>

            {/* Memory with alerts */}
            <div>
              <div className="flex items-center justify-between">
                <div className="text-sm font-medium flex items-center gap-2">
                  <HardDrive className="h-4 w-4 text-muted-foreground" />
                  Memory Usage
                </div>
                <div className="text-sm text-muted-foreground">{memoryUsage === null ? "미수집" : `${memoryUsage.toFixed(2)}%`}</div>
              </div>
              <Progress value={memoryUsage ?? 0} className="mt-2 mb-3" />

              {/* Removed inline alerts under memory to avoid duplication with Alerts card */}
              <div className="mt-3 pt-3 border-t">
                <Button
                  variant="ghost"
                  size="sm"
                  className="w-full text-xs"
                  onClick={() => {
                    if (onNavigateToMonitoring) {
                      onNavigateToMonitoring('details')
                    }
                    try {
                      const url = new URL(window.location.href)
                      url.searchParams.set('view', 'monitoring')
                      url.searchParams.set('tab', 'details')
                      window.history.replaceState({}, '', url.toString())
                    } catch {}
                    if (!onNavigateToMonitoring) {
                      router.push('/?view=monitoring&tab=details')
                    }
                  }}
                >
                  View Details
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Active Deployments & Recent Commands */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle>Active Deployments</CardTitle>
              <CardDescription>Latest repository workloads</CardDescription>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onNavigateToDeployments?.()}
            >
              View All
            </Button>
          </CardHeader>
          <CardContent className="space-y-3">
            {repositories.length > 0 ? (
              repositories.map((repo) => {
                const getStatusBadge = (status: string | undefined) => {
                  if (!status) {
                    return <Badge variant="outline" className="text-xs">No Deploy</Badge>
                  }

                  // 배포 기록의 상태다 (Pod 실행 여부는 관측하지 않음)
                  switch (status) {
                    case "SUCCESS":
                      return (
                        <Badge className="bg-green-500 text-xs">
                          <CheckCircle className="mr-1 h-2 w-2" />
                          Succeeded
                        </Badge>
                      )
                    case "PENDING":
                    case "UPLOADING_SOURCE":
                    case "BUILDING":
                    case "DEPLOYING":
                      return (
                        <Badge className="bg-blue-500 text-xs">
                          <Clock className="mr-1 h-2 w-2" />
                          Deploying
                        </Badge>
                      )
                    case "FAILED":
                      return (
                        <Badge variant="destructive" className="text-xs">
                          <AlertTriangle className="mr-1 h-2 w-2" />
                          Failed
                        </Badge>
                      )
                    default:
                      return <Badge variant="outline" className="text-xs">{status}</Badge>
                  }
                }

                return (
                  <div
                    key={repo.full_name}
                    className="flex items-start justify-between p-3 border rounded-lg hover:bg-accent/50 transition-colors cursor-pointer group"
                    onClick={() => onNavigateToDeployments?.()}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <Github className="h-3 w-3 shrink-0" />
                        <p className="text-sm font-medium truncate group-hover:text-primary transition-colors">
                          {repo.full_name}
                        </p>
                      </div>
                      {repo.latest_deployment ? (
                        <div className="space-y-1">
                          <div className="flex items-center gap-2 text-xs text-muted-foreground whitespace-nowrap overflow-hidden">
                            <span className="font-mono bg-muted px-1 rounded truncate max-w-[180px]">
                              {repo.latest_deployment.commit_hash.slice(0, 7)}
                            </span>
                            <span>•</span>
                            <span>{repo.latest_deployment.branch_name}</span>
                          </div>
                        </div>
                      ) : (
                        <div className="space-y-1">
                          <p className="text-xs text-muted-foreground">No deployments</p>
                        </div>
                      )}
                    </div>
                    <div className="ml-2 shrink-0">
                      {getStatusBadge(repo.latest_deployment?.status)}
                    </div>
                  </div>
                )
              })
            ) : (
              <div className="text-center py-8 text-muted-foreground">
                <Server className="h-8 w-8 mx-auto mb-2 opacity-50" />
                <p className="text-sm">No active deployments</p>
              </div>
            )}
          </CardContent>
        </Card>

        <RecentCommands onNavigateToChat={onNavigateToChat} />
      </div>
    </div>
  )
}
