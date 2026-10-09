"use client"

import { useEffect, useState, useRef } from "react"
import { useGlobalWebSocket } from "@/hooks/use-global-websocket"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { CheckCircle, XCircle, Clock, Zap, GitBranch, Package, Rocket } from "lucide-react"
import { formatDuration } from "@/lib/utils"
import { formatKst } from "@/lib/time"

interface DeploymentProgressProps {
  deploymentId: string | number
  repository: string
  status: "running" | "success" | "failed" | "completed" | "pending" | "unknown" | "cancelled"
  stages: {
    sourcecommit: {
      status: "success" | "failed" | null
      duration: number | null
      progress?: number
      elapsed_time?: number
      message?: string
      started_at?: string
      completed_at?: string
    }
    sourcebuild: {
      status: "success" | "failed" | null
      duration: number | null
      progress?: number
      elapsed_time?: number
      message?: string
      started_at?: string
      completed_at?: string
    }
    sourcedeploy: {
      status: "success" | "failed" | null
      duration: number | null
      progress?: number
      elapsed_time?: number
      message?: string
      started_at?: string
      completed_at?: string
    }
  }
  timing: {
    started_at: string
    completed_at: string | null
    total_duration: number | null
  }
  error?: {
    message: string | null
    stage: string | null
  } | null
  auto_deploy_enabled: boolean
}

const STAGE_CONFIG = {
  sourcecommit: {
    name: "Source Commit",
    description: "Mirroring repository to SourceCommit",
    icon: GitBranch,
    color: "blue"
  },
  sourcebuild: {
    name: "Source Build",
    description: "Building Docker image",
    icon: Package,
    color: "yellow"
  },
  sourcedeploy: {
    name: "Source Deploy",
    description: "Deploying to Kubernetes",
    icon: Rocket,
    color: "green"
  }
}

export function DeploymentProgress({ 
  deploymentId, 
  repository, 
  status, 
  stages, 
  timing, 
  error,
  auto_deploy_enabled 
}: DeploymentProgressProps) {
  const [currentProgress, setCurrentProgress] = useState(0)
  const [isAnimating, setIsAnimating] = useState(false)
  
  // 각 스테이지별 독립적인 카운터
  const [stageCounters, setStageCounters] = useState<{[key: string]: number}>({
    sourcecommit: 0,
    sourcebuild: 0,
    sourcedeploy: 0
  })
  // Note: 전역 WS 매니저는 훅 내부에서 관리되므로 외부 접근 코드는 제거
  // helper: 보정된 now
  const getCorrectedNowMs = () => {
    try {
      // 동적 접근: 훅 외부 인스턴스 접근이 불가하므로 window 전역에 저장하는 방식을 함께 고려할 수 있음
      // 현재 구현에서는 use-global-websocket 내부 인스턴스에 getter를 노출했으므로 간단 래퍼를 사용하지 않고
      // 메시지에서 전달된 started_at/elapsed_time 우선 규칙을 적용
      return Date.now()
    } catch {
      return Date.now()
    }
  }

  // 타임존 정보가 없는 ISO 문자열(naive)을 UTC로 간주하여 파싱
  const parseIsoAsUtc = (iso?: string | null): Date | null => {
    if (!iso) return null
    try {
      const hasTz = /Z|[+-]\d{2}:?\d{2}$/.test(iso)
      const fixed = hasTz ? iso : `${iso.replace(/\s+/g, 'T')}${iso.includes('T') ? '' : ''}${hasTz ? '' : 'Z'}`
      // 위 치환은 이미 T가 있는 경우 그대로 두고, TZ 없으면 Z를 붙임
      return new Date(fixed)
    } catch {
      try { return new Date(iso) } catch { return null }
    }
  }

  
  // 각 스테이지별 카운터 인터벌 참조
  const counterIntervals = useRef<{[key: string]: NodeJS.Timeout | null}>({
    sourcecommit: null,
    sourcebuild: null,
    sourcedeploy: null
  })

  // 각 스테이지별 카운터 관리 (한 번에 첫 번째 진행중 스테이지만 카운트)
  useEffect(() => {
    const keysInOrder = Object.keys(STAGE_CONFIG)
    const firstRunningKey = keysInOrder.find(k => (stages as any)[k]?.status === null)

    Object.entries(stages).forEach(([stageKey, stage]) => {
      const isThisRunning = stage.status === null && stageKey === firstRunningKey
      const isCompleted = stage.status === "success"
      const isFailed = stage.status === "failed"
      
      // 진행 중인(첫 번째 null) 스테이지의 카운터 시작
      if (isThisRunning && !counterIntervals.current[stageKey]) {
        // 카운터가 이미 시작되지 않은 경우에만 0으로 초기화
        setStageCounters(prev => {
          if (prev[stageKey] === undefined || prev[stageKey] === 0) {
            return { ...prev, [stageKey]: 0 }
          }
          return prev
        })
        
        const startedAtMs = stage.started_at ? new Date(stage.started_at).getTime() : getCorrectedNowMs()
        counterIntervals.current[stageKey] = setInterval(() => {
          setStageCounters(prev => {
            // 서버에서 started_at이 있으면 그 기준으로 경과시간 계산 (표시만 보정)
            const now = getCorrectedNowMs()
            const computed = Math.max(0, Math.round((now - startedAtMs) / 1000))
            const base = prev[stageKey] ?? 0
            const newCount = Math.max(base + 1, computed)
            return { 
              ...prev, 
              [stageKey]: newCount
            }
          })
        }, 1000) // 1초마다 증가
      }
      
      // 완료/실패 또는 더 이상 활성 스테이지가 아니면 카운터 정지 (최종 시간 유지)
      if (((isCompleted || isFailed) || (!isThisRunning && counterIntervals.current[stageKey])) && counterIntervals.current[stageKey]) {
        clearInterval(counterIntervals.current[stageKey]!)
        counterIntervals.current[stageKey] = null
        // 카운터는 최종 시간을 유지 (리셋하지 않음)
      }
    })
  }, [stages])

  // 컴포넌트 언마운트 시 모든 인터벌 정리 (의존성 없음)
  useEffect(() => {
    return () => {
      Object.keys(counterIntervals.current).forEach(key => {
        const interval = counterIntervals.current[key]
        if (interval) clearInterval(interval)
        counterIntervals.current[key] = null
      })
    }
  }, [])

  // Calculate overall progress
  useEffect(() => {
    const stageValues = Object.values(stages)
    const completedStages = stageValues.filter(stage => stage.status === "success").length
    const totalStages = stageValues.length
    const baseProgress = (completedStages / totalStages) * 100

    // Add some animation for running stages
    if (status === "running") {
      const runningStage = stageValues.find(stage => stage.status === null)
      if (runningStage && typeof runningStage.progress === "number") {
        // Use real-time progress from WebSocket
        const runningStageIndex = Math.max(0, stageValues.findIndex(stage => stage.status === null))
        const progressFromCompletedStages = (runningStageIndex / totalStages) * 100
        const progressFromCurrentStage = (runningStage.progress / 100) * (100 / totalStages)
        
        const totalProgress = Math.max(0, Math.min(100, progressFromCompletedStages + progressFromCurrentStage))
        setCurrentProgress(totalProgress)
        setIsAnimating(true)
      } else {
        // Fallback to simulated progress
        const stageProgress = 50
        const runningStageIndex = Math.max(0, stageValues.findIndex(stage => stage.status === null))
        const progressFromCompletedStages = (runningStageIndex / totalStages) * 100
        const progressFromCurrentStage = (stageProgress / 100) * (100 / totalStages)
        
        const totalProgress = Math.max(0, Math.min(100, progressFromCompletedStages + progressFromCurrentStage))
        setCurrentProgress(totalProgress)
        setIsAnimating(true)
      }
    } else if (status === "success" || status === "completed") {
      setCurrentProgress(100)
      setIsAnimating(false)
    } else {
      setCurrentProgress(Math.max(0, baseProgress))
      setIsAnimating(false)
    }
  }, [stages, status])

  const getStageIcon = (stageStatus: "success" | "failed" | null) => {
    if (stageStatus === "success") {
      return <CheckCircle className="w-5 h-5 text-green-500" />
    } else if (stageStatus === "failed") {
      return <XCircle className="w-5 h-5 text-red-500" />
    } else {
      return <Clock className="w-5 h-5 text-blue-500 animate-spin" />
    }
  }

  // 가독성을 위한 스테이지 소요 시간 계산 헬퍼
  const calculateStageDuration = (stage: any, counterValue: number): number => {
    if (stage?.duration != null) return stage.duration
    if (stage?.completed_at && stage?.started_at) {
      const startTime = new Date(stage.started_at).getTime()
      const endTime = new Date(stage.completed_at).getTime()
      return Math.max(0, Math.round((endTime - startTime) / 1000))
    }
    return stage?.elapsed_time ?? counterValue ?? 0
  }

  const getStageColor = (stageStatus: "success" | "failed" | null, stageKey: string) => {
    if (stageStatus === "success") {
      return "bg-green-100 border-green-200"
    } else if (stageStatus === "failed") {
      return "bg-red-100 border-red-200"
    } else {
      return "bg-blue-100 border-blue-200"
    }
  }

  const getStatusBadge = () => {
    switch (status) {
      case "running":
        return <Badge variant="secondary" className="bg-blue-100 text-blue-800"><Clock className="w-3 h-3 mr-1" />Running</Badge>
      case "success":
      case "completed":
        return <Badge variant="secondary" className="bg-green-100 text-green-800"><CheckCircle className="w-3 h-3 mr-1" />Success</Badge>
      case "failed":
        return <Badge variant="secondary" className="bg-red-100 text-red-800"><XCircle className="w-3 h-3 mr-1" />Failed</Badge>
      case "pending":
        return <Badge variant="secondary" className="bg-yellow-100 text-yellow-800"><Clock className="w-3 h-3 mr-1" />Pending</Badge>
      default:
        return <Badge variant="outline">Unknown</Badge>
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center space-x-2">
              <span>Deployment #{deploymentId}</span>
              {auto_deploy_enabled && (
                <Badge variant="outline" className="bg-green-100 text-green-800">
                  <Zap className="w-3 h-3 mr-1" />
                  Auto Deploy
                </Badge>
              )}
            </CardTitle>
            <CardDescription>{repository}</CardDescription>
          </div>
          {getStatusBadge()}
        </div>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Overall Progress */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Overall Progress</span>
            <span className="text-sm text-muted-foreground">{Math.round(currentProgress)}%</span>
          </div>
          <Progress 
            value={currentProgress} 
            className={`h-2 ${isAnimating ? 'animate-pulse' : ''}`}
          />
        </div>

        {/* Deployment Plan */}
        <div className="space-y-6">
          <h4 className="text-sm font-medium">Deployment Plan</h4>
          <div className="space-y-4">
            {stages && Object.entries(stages).map(([stageKey, stage], index) => {
              const config = STAGE_CONFIG[stageKey as keyof typeof STAGE_CONFIG]
              const IconComponent = config.icon
              const isCompleted = stage.status === "success" || (stage as any)?.completed_at != null || (stage as any)?.duration != null
              const isFailed = stage.status === "failed"
              // ✅ 개선: started_at이 있거나 progress가 0 이상이면 실행 중으로 간주
              const isRunning = stage.status === null &&
                                !isCompleted &&
                                !isFailed &&
                                ((stage as any)?.started_at != null ||
                                 (typeof stage.progress === 'number' && stage.progress >= 0))
              const isPending = !isCompleted && !isFailed && !isRunning
              
              return (
                <div key={stageKey} className="relative">
                  {/* Stage Number and Connector */}
                  <div className="flex items-start">
                    <div className={`flex items-center justify-center w-8 h-8 rounded-full border-2 ${
                      isCompleted 
                        ? "bg-green-500 border-green-500 text-white" 
                        : isFailed
                        ? "bg-red-500 border-red-500 text-white"
                        : isRunning
                        ? "bg-blue-500 border-blue-500 text-white"
                        : "bg-gray-200 border-gray-300 text-gray-500"
                    }`}>
                      {isCompleted ? (
                        <CheckCircle className="w-4 h-4" />
                      ) : isFailed ? (
                        <XCircle className="w-4 h-4" />
                      ) : (
                        <span className="text-sm font-medium">{index + 1}</span>
                      )}
                    </div>
                    
                    {/* Connector Line */}
                    {index < Object.keys(stages).length - 1 && (
                      <div className={`absolute left-4 top-8 w-0.5 h-8 ${
                        isCompleted ? "bg-green-300" : "bg-gray-200"
                      }`} />
                    )}
                    
                    {/* Stage Content */}
                    <div className={`ml-4 flex-1 p-4 rounded-lg border ${
                      isCompleted 
                        ? "bg-green-50 border-green-200" 
                        : isFailed
                        ? "bg-red-50 border-red-200"
                        : isRunning
                        ? "bg-blue-50 border-blue-200"
                        : "bg-gray-50 border-gray-200"
                    }`}>
                      <div className="flex items-center justify-between">
                        <div className="flex items-center space-x-3">
                          <div className={`p-2 rounded-full ${
                            isCompleted 
                              ? "bg-green-100 text-green-600" 
                              : isFailed
                              ? "bg-red-100 text-red-600"
                              : isRunning
                              ? "bg-blue-100 text-blue-600"
                              : "bg-gray-100 text-gray-400"
                          }`}>
                            <IconComponent className="w-4 h-4" />
                          </div>
                          <div>
                            <div className="text-sm font-medium">{config.name}</div>
                            <div className="text-xs text-muted-foreground">{config.description}</div>
                          </div>
                        </div>
                        <div className="text-right space-y-1 min-w-[200px] flex flex-col items-end">
                          {(isCompleted || isFailed || stage.duration) ? (
                            <div className="space-y-1">
                              <div className={`text-sm font-medium ${isFailed ? 'text-red-600' : 'text-green-600'}`}>
                                {isFailed ? '✗' : '✓'} { calculateStageDuration(stage, stageCounters[stageKey]) }s
                              </div>
                              {/* Completed/Failed: 진행률 바 표시하지 않음 */}
                            </div>
                          ) : isRunning ? (
                            <div className="space-y-1">
                              <div className="text-xs text-muted-foreground">
                                {typeof stage.progress === "number" ? `${stage.progress}%` : "0%"} ({stage.started_at ? Math.max(0, Math.round((getCorrectedNowMs() - new Date(stage.started_at).getTime())/1000)) : stageCounters[stageKey]}s)
                              </div>
                              <div className="text-sm text-muted-foreground">
                                {stage.message || "Running..."}
                              </div>
                               <Progress 
                                 value={typeof stage.progress === "number" ? stage.progress : 0} 
                                 className="w-28 h-1 ml-auto"
                               />
                            </div>
                          ) : (
                            <div className="space-y-1">
                              <div className="text-sm text-muted-foreground text-gray-400">Waiting...</div>
                              {/* Waiting: 진행률 바 표시하지 않음 */}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {/* Error Information */}
        {error && (
          <div className="p-3 bg-red-50 border border-red-200 rounded-lg">
            <div className="flex items-start space-x-2">
              <XCircle className="w-4 h-4 text-red-500 mt-0.5" />
              <div>
                <div className="text-sm font-medium text-red-800">
                  Error in {error.stage}
                </div>
                <div className="text-sm text-red-600 mt-1">
                  {error.message}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Timing Information */}
        <div className="text-xs text-muted-foreground space-y-1">
          {timing?.started_at && (
            <div>Started: {formatKst(timing.started_at, true)}</div>
          )}
          {timing?.completed_at && (
            <div>Completed: {formatKst(timing.completed_at, true)}</div>
          )}
          {timing?.total_duration && (
            <div>Total Duration: {formatDuration(timing.total_duration)}</div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
