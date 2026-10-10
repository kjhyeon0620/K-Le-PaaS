"use client"

import { useEffect, useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { AlertTriangle, ArrowLeft, ChevronRight, RefreshCw, Terminal } from "lucide-react"
import {
  api,
  type DeploymentDetail,
  type DeploymentStatus,
  type DeploymentSummary,
  type TriggerSource,
} from "@/lib/api"
import { formatKst } from "@/lib/time"
import { formatDuration } from "@/lib/utils"
import { DeploymentLogsDialog } from "@/components/deployment-logs-dialog"

// 배포 요청 목록과 상세 (#95). 기록되지 않은 값은 "기록 없음"으로 보이고 현재 값으로 채우지 않는다.

const TRIGGER_LABEL: Record<TriggerSource, string> = {
  WEB: "웹 콘솔",
  CLI: "CLI",
  CI_TOKEN: "CI (배포 전용 토큰)",
  CI_OIDC: "GitHub Actions",
  WEBHOOK: "GitHub push webhook",
  NLP: "자연어 명령",
}

const CHANGE_LABEL: Record<DeploymentDetail["changes"][number]["kind"], string> = {
  CHANGED: "변경",
  ADDED: "추가",
  REMOVED: "삭제",
  VALUE_CHANGED: "값 변경됨",
  UNKNOWN: "판정 불가",
}

const NOT_RECORDED = "기록 없음"

function StatusBadge({ status }: { status: DeploymentStatus }) {
  if (status === "SUCCESS") return <Badge className="bg-green-500">성공</Badge>
  if (status === "FAILED") return <Badge variant="destructive">실패</Badge>
  if (status === "CANCELED") return <Badge variant="secondary">대체됨</Badge>
  return <Badge className="bg-blue-500">{status}</Badge>
}

function durationSeconds(start: string | null, end: string | null): number | null {
  if (!start || !end) return null
  return Math.max(0, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 1000))
}

function shortImage(image: string | null | undefined): string {
  if (!image) return "-"
  const tag = image.split(":").pop() ?? image
  return tag.length > 18 ? tag.slice(0, 18) : tag
}

function Field({ label, children, wide = false }: { label: string; children: React.ReactNode; wide?: boolean }) {
  return (
    <div className={wide ? "sm:col-span-2 lg:col-span-3" : ""}>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-1 text-sm break-all">{children}</div>
    </div>
  )
}

export function DeploymentHistory({
  repositoryId,
  repositoryName,
  onOpen,
  onBack,
}: {
  repositoryId: number
  repositoryName: string
  onOpen: (deploymentId: number) => void
  onBack: () => void
}) {
  const [items, setItems] = useState<DeploymentSummary[] | null>(null)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    api.getRepositoryDeployments(repositoryId, page)
      .then((result) => {
        if (cancelled) return
        setItems(result.content)
        setTotalPages(result.total_pages)
      })
      .catch((e) => !cancelled && setError(String(e)))
    return () => { cancelled = true }
  }, [repositoryId, page])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="sm" onClick={onBack}>
          <ArrowLeft className="mr-2 h-4 w-4" />
          저장소 목록
        </Button>
        <h2 className="text-xl font-bold tracking-tight">{repositoryName} 배포 목록</h2>
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>배포 목록을 불러오지 못했습니다</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="p-0">
          {items === null && !error ? (
            <div className="flex justify-center py-10"><RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" /></div>
          ) : items && items.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted-foreground">배포 기록이 없습니다</p>
          ) : (
            <ul className="divide-y">
              {items?.map((d) => (
                <li key={d.id}>
                  <button
                    type="button"
                    onClick={() => onOpen(d.id)}
                    className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-muted/50 focus-visible:bg-muted/50 focus-visible:outline-none"
                  >
                    <span className="w-12 font-mono text-sm text-muted-foreground">#{d.id}</span>
                    <StatusBadge status={d.status} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-mono text-sm">
                        {d.commit_hash.slice(0, 7)} · {shortImage(d.image_uri)}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {formatKst(d.started_at)} · {d.trigger_source ? TRIGGER_LABEL[d.trigger_source] : NOT_RECORDED}
                        {d.fail_reason ? ` · ${d.fail_reason}` : ""}
                      </span>
                    </span>
                    <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
          <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>다음</Button>
        </div>
      )}
    </div>
  )
}

export function DeploymentRequestDetail({ deploymentId, onBack }: { deploymentId: number; onBack: () => void }) {
  const [detail, setDetail] = useState<DeploymentDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [logsOpen, setLogsOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    setDetail(null)
    setError(null)
    api.getDeploymentDetail(deploymentId)
      .then((result) => !cancelled && setDetail(result))
      .catch((e) => !cancelled && setError(String(e)))
    return () => { cancelled = true }
  }, [deploymentId])

  const d = detail?.deployment

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="sm" onClick={onBack}>
          <ArrowLeft className="mr-2 h-4 w-4" />
          배포 목록
        </Button>
        <h2 className="text-xl font-bold tracking-tight">배포 #{deploymentId}</h2>
        {d && <StatusBadge status={d.status} />}
        {d && <span className="text-sm text-muted-foreground">{d.repository_name}</span>}
        {d && (
          <Button variant="outline" size="sm" className="ml-auto" onClick={() => setLogsOpen(true)}>
            <Terminal className="mr-2 h-4 w-4" />
            로그·이벤트
          </Button>
        )}
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>배포 상세를 불러오지 못했습니다</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {!detail && !error && (
        <div className="flex justify-center py-10"><RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" /></div>
      )}

      {detail && d && (
        <>
          {(detail.explanation || d.fail_reason) && (
            <Alert variant={d.status === "CANCELED" ? "default" : "destructive"}>
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>{detail.explanation?.title ?? "실패"}</AlertTitle>
              <AlertDescription className="space-y-2">
                {detail.explanation && <p>{detail.explanation.summary}</p>}
                {d.fail_reason && <p className="font-mono text-xs break-all">{d.fail_reason}</p>}
                {detail.explanation && detail.explanation.checks.length > 0 && (
                  <div>
                    <p className="font-medium">확인할 것</p>
                    <ul className="list-disc pl-5">
                      {detail.explanation.checks.map((c) => <li key={c}>{c}</li>)}
                    </ul>
                  </div>
                )}
              </AlertDescription>
            </Alert>
          )}

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">요청</CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="요청 경로">{d.trigger_source ? TRIGGER_LABEL[d.trigger_source] : NOT_RECORDED}</Field>
              <Field label="요청자">
                {detail.requested_by ? (detail.requested_by.name ?? `사용자 #${detail.requested_by.user_id}`)
                  : d.trigger_source ? "없음 (CI·webhook)" : NOT_RECORDED}
              </Field>
              <Field label="승인 명령">
                {detail.command ? (
                  <span>#{detail.command.id} {detail.command.raw_command ? `"${detail.command.raw_command}"` : ""}</span>
                ) : "-"}
              </Field>
              <Field label="Branch · Commit"><span className="font-mono">{d.branch_name} · {d.commit_hash}</span></Field>
              <Field label="이미지" wide><span className="font-mono">{d.image_uri ?? "-"}</span></Field>
              <Field label="이미지 digest (실행 중 관측)" wide>
                <span className="font-mono">{d.image_digest ?? (d.status === "SUCCESS" ? NOT_RECORDED : "-")}</span>
              </Field>
              <Field label="시작">{formatKst(d.started_at)}</Field>
              <Field label="소요 시간">{formatDuration(durationSeconds(d.started_at, d.finished_at))}</Field>
              <Field label="실패 종류">{d.failure_kind ?? (d.status === "FAILED" ? NOT_RECORDED : "-")}</Field>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">직전 성공 배포 대비 변경</CardTitle>
              <CardDescription>
                {detail.comparison === "NO_PREVIOUS" && "비교할 직전 성공 배포가 없습니다."}
                {detail.comparison === "NOT_RECORDED" && detail.previous_success &&
                  `배포 #${detail.previous_success.id}와 비교. 한쪽 배포의 설정 기록이 없어 이미지만 비교했습니다.`}
                {detail.comparison === "AVAILABLE" && detail.previous_success &&
                  `배포 #${detail.previous_success.id}와 비교 (이미지와 배포 설정)`}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {detail.comparison !== "NO_PREVIOUS" && detail.changes.length === 0 ? (
                <p className="text-sm text-muted-foreground">변경 없음</p>
              ) : (
                <ul className="divide-y">
                  {detail.changes.map((c) => (
                    <li key={c.field} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                      <span className="font-mono">{c.field}</span>
                      <Badge variant={c.kind === "REMOVED" ? "destructive" : c.kind === "UNKNOWN" ? "outline" : "secondary"}>
                        {CHANGE_LABEL[c.kind]}
                      </Badge>
                      {(c.before !== null || c.after !== null) && (
                        <span className="w-full font-mono text-xs text-muted-foreground break-all">
                          {c.before ?? "-"} → {c.after ?? "-"}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">적용한 배포 설정</CardTitle>
              <CardDescription>
                {detail.config ? "이 요청이 적용한 값입니다. env 값은 저장하지 않고 이름만 보여 줍니다."
                  : "이 배포는 설정이 기록되지 않았습니다 (기록 도입 이전 배포이거나 적용 전에 끝남)."}
              </CardDescription>
            </CardHeader>
            {detail.config && (
              <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <Field label="빌드 방식">{detail.config.build_strategy ?? "-"}</Field>
                <Field label="Replicas">{detail.config.min_replicas} ~ {detail.config.max_replicas}</Field>
                <Field label="포트">{detail.config.container_port}</Field>
                <Field label="Service">
                  {detail.config.service_type ?? "-"}{detail.config.node_port ? ` ${detail.config.node_port}` : ""}
                </Field>
                <Field label="Health probe">
                  {detail.config.health_probe?.path ? `${detail.config.health_probe.path}` : "없음"}
                </Field>
                <Field label="Pull secret">
                  {detail.config.image_pull_secret_enabled ? (detail.config.image_pull_secret_name ?? "기본값") : "사용 안 함"}
                </Field>
                <Field label="env" wide>
                  {detail.config.env_names.length === 0 ? "없음" : (
                    <span className="flex flex-wrap gap-1">
                      {detail.config.env_names.map((n) => <Badge key={n} variant="outline" className="font-mono">{n}</Badge>)}
                    </span>
                  )}
                </Field>
                {(detail.config.env_from_config_maps.length > 0 || detail.config.env_from_secrets.length > 0) && (
                  <Field label="envFrom (ConfigMap · Secret 이름)" wide>
                    <span className="font-mono">
                      {[...detail.config.env_from_config_maps, ...detail.config.env_from_secrets].join(", ")}
                    </span>
                  </Field>
                )}
              </CardContent>
            )}
          </Card>

          <DeploymentLogsDialog
            open={logsOpen}
            onOpenChange={setLogsOpen}
            deploymentId={deploymentId}
            appName={d.repository_name.replace("/", "-").toLowerCase()}
          />
        </>
      )}
    </div>
  )
}
