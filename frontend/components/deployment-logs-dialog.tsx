"use client"

import { useCallback, useEffect, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Label } from "@/components/ui/label"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { AlertCircle, Copy, Download, RefreshCw, Terminal } from "lucide-react"
import { api, type DeploymentLogs, type DeploymentPodLog } from "@/lib/api"
import { formatKst } from "@/lib/time"
import { toast } from "sonner"

interface DeploymentLogsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  deploymentId: number
  appName: string
}

const OBSERVATION_LABEL: Record<DeploymentLogs["observation"], string> = {
  AVAILABLE: "이 배포의 Pod",
  NOT_CURRENT: "이 배포의 Pod 없음",
  UNAVAILABLE: "관측 실패",
}

function podSummary(pod: DeploymentPodLog): string {
  const state = [pod.state, pod.state_reason].filter(Boolean).join(" ") || "상태 없음"
  const last = pod.last_termination_reason
    ? `, 마지막 종료 ${pod.last_termination_reason}(exit ${pod.last_exit_code})`
    : ""
  return `${pod.phase ?? "-"} · ready=${pod.ready} · restarts=${pod.restart_count} · ${state}${last}`
}

// 배포 요청 한 건의 실패 원인과, 그 요청이 적용한 Pod의 로그·이벤트를 보여 준다 (#54).
// 다른 요청의 Pod 로그는 보여 주지 않고, 관측할 수 없으면 그 이유를 표시한다.
export function DeploymentLogsDialog({ open, onOpenChange, deploymentId, appName }: DeploymentLogsDialogProps) {
  const [data, setData] = useState<DeploymentLogs | null>(null)
  const [selectedPod, setSelectedPod] = useState("")
  const [lines, setLines] = useState(100)
  const [previous, setPrevious] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fetchLogs = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const response = await api.getDeploymentLogs(deploymentId, lines)
      setData(response)
      setSelectedPod((current) =>
        response.pods.some((pod) => pod.name === current) ? current : response.pods[0]?.name ?? ""
      )
    } catch (err) {
      setData(null)
      setError("배포 로그를 조회하지 못했습니다.")
      console.error("Failed to fetch deployment logs:", err)
    } finally {
      setLoading(false)
    }
  }, [deploymentId, lines])

  useEffect(() => {
    if (open) fetchLogs()
  }, [open, fetchLogs])

  const pod = data?.pods.find((p) => p.name === selectedPod) ?? null
  const shownLines = pod ? (previous && pod.previous_logs ? pod.previous_logs : pod.logs) : []
  const logText = shownLines.join("\n")

  const handleCopyLogs = () => {
    if (!logText) return
    navigator.clipboard.writeText(logText)
    toast.success("로그가 클립보드에 복사되었습니다")
  }

  const handleDownloadLogs = () => {
    if (!logText || !pod) return
    const blob = new Blob([logText], { type: "text/plain" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `deployment-${deploymentId}-${pod.name}${previous ? "-previous" : ""}.txt`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    toast.success("로그가 다운로드되었습니다")
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[min(90vw,1100px)] w-[90vw] sm:w-auto max-h-[82vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base">
            <span className="inline-flex h-6 w-6 items-center justify-center rounded bg-muted">
              <Terminal className="h-4 w-4 text-muted-foreground" />
            </span>
            <span className="font-semibold">{appName}</span>
            <span className="text-muted-foreground">배포 #{deploymentId} 로그</span>
          </DialogTitle>
          <DialogDescription>
            이 배포 요청이 적용한 Pod의 조회 시점 상태, 최근 로그, Kubernetes 이벤트입니다. 로그는 저장되지 않습니다.
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 flex flex-col space-y-3 min-h-0">
          {error && (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>조회 실패</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          {data && (
            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant={data.status === "SUCCESS" ? "default" : data.status === "FAILED" ? "destructive" : "secondary"}>
                  {data.status}
                </Badge>
                <Badge variant={data.observation === "AVAILABLE" ? "outline" : "secondary"}>
                  {OBSERVATION_LABEL[data.observation]}
                </Badge>
              </div>
              {data.fail_reason && (
                <Alert variant="destructive">
                  <AlertCircle className="h-4 w-4" />
                  <AlertTitle>실패 원인</AlertTitle>
                  <AlertDescription className="font-mono text-xs break-all">{data.fail_reason}</AlertDescription>
                </Alert>
              )}
              {data.observation_message && (
                <Alert>
                  <AlertCircle className="h-4 w-4" />
                  <AlertTitle>{OBSERVATION_LABEL[data.observation]}</AlertTitle>
                  <AlertDescription>{data.observation_message}</AlertDescription>
                </Alert>
              )}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-3 p-3 bg-muted/30 rounded-lg border">
            <div className="flex items-center gap-2">
              <Label htmlFor="pod-select" className="text-sm font-medium">Pod</Label>
              <Select value={selectedPod} onValueChange={(value) => { setSelectedPod(value); setPrevious(false) }}
                disabled={!data || data.pods.length === 0}>
                <SelectTrigger id="pod-select" className="w-64">
                  <SelectValue placeholder="Pod 없음" />
                </SelectTrigger>
                <SelectContent>
                  {data?.pods.map((p) => (
                    <SelectItem key={p.name} value={p.name}>
                      <span className="font-mono text-sm">{p.name}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-center gap-2">
              <Label htmlFor="lines-select" className="text-sm font-medium">줄 수</Label>
              <Select value={lines.toString()} onValueChange={(v) => setLines(Number(v))}>
                <SelectTrigger id="lines-select" className="w-24">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="50">50</SelectItem>
                  <SelectItem value="100">100</SelectItem>
                  <SelectItem value="200">200</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-center gap-2">
              <Switch id="previous-toggle" checked={previous} onCheckedChange={setPrevious}
                disabled={!pod?.previous_logs} />
              <Label htmlFor="previous-toggle" className="text-sm">직전 컨테이너 로그</Label>
            </div>

            <Button variant="outline" size="sm" onClick={fetchLogs} disabled={loading}>
              <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />
              새로고침
            </Button>
            <div className="flex items-center gap-2 ml-auto">
              <Button variant="secondary" size="sm" onClick={handleCopyLogs} disabled={!logText}>
                <Copy className="mr-2 h-4 w-4" />
                로그 복사
              </Button>
              <Button variant="secondary" size="sm" onClick={handleDownloadLogs} disabled={!logText}>
                <Download className="mr-2 h-4 w-4" />
                다운로드
              </Button>
            </div>
          </div>

          <Tabs defaultValue="logs" className="flex-1 min-h-0 flex flex-col">
            <TabsList>
              <TabsTrigger value="logs">로그</TabsTrigger>
              <TabsTrigger value="events">이벤트 ({data?.events.length ?? 0})</TabsTrigger>
            </TabsList>

            <TabsContent value="logs" className="flex-1 min-h-0">
              {pod ? (
                <div className="h-full rounded-lg border bg-background overflow-hidden flex flex-col">
                  <div className="px-3 py-2 bg-muted/40 border-b text-xs space-y-1">
                    <div className="font-mono">{podSummary(pod)}</div>
                    {pod.state_message && <div className="text-muted-foreground break-all">{pod.state_message}</div>}
                    {pod.logs_error && <div className="text-destructive break-all">로그 조회 불가: {pod.logs_error}</div>}
                  </div>
                  <ScrollArea className="flex-1 min-h-0">
                    <pre className="p-3 bg-[rgb(18,18,18)] text-[rgb(210,210,210)] text-xs font-mono whitespace-pre-wrap break-all leading-5 min-h-24">
                      {logText || "(로그 없음)"}
                    </pre>
                  </ScrollArea>
                </div>
              ) : (
                <div className="flex items-center justify-center h-32 text-sm text-muted-foreground">
                  {loading ? "불러오는 중..." : "표시할 Pod가 없습니다"}
                </div>
              )}
            </TabsContent>

            <TabsContent value="events" className="flex-1 min-h-0">
              <ScrollArea className="h-full max-h-[40vh]">
                {data && data.events.length > 0 ? (
                  <ul className="space-y-2 text-sm">
                    {data.events.map((event, index) => (
                      <li key={`${event.object}-${event.reason}-${index}`} className="rounded border p-2 space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant={event.type === "Warning" ? "destructive" : "outline"}>{event.type ?? "-"}</Badge>
                          <span className="font-medium">{event.reason}</span>
                          <span className="font-mono text-xs text-muted-foreground">{event.object}</span>
                          <span className="text-xs text-muted-foreground">
                            {formatKst(event.last_seen, true)}{event.count ? ` · ${event.count}회` : ""}
                          </span>
                        </div>
                        <p className="text-xs break-all">{event.message}</p>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-muted-foreground p-2">
                    관측된 이벤트가 없습니다. Kubernetes 이벤트는 보존 기간(기본 1시간)이 지나면 사라집니다.
                  </p>
                )}
              </ScrollArea>
            </TabsContent>
          </Tabs>
        </div>
      </DialogContent>
    </Dialog>
  )
}
