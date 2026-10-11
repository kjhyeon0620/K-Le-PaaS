"use client"

import { useState, useEffect } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { AlertCircle, RotateCcw } from "lucide-react"
import { api, ConfigChange, RecoveryCandidate, RecoveryPlan } from "@/lib/api"
import { useToast } from "@/hooks/use-toast"
import { formatKst } from "@/lib/time"

interface RollbackDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  owner: string
  repo: string
  onRollbackSuccess?: () => void
}

const ENV_KIND_LABEL: Record<ConfigChange["kind"], string> = {
  CHANGED: "변경",
  ADDED: "대상에만 있음",
  REMOVED: "현재에만 있음",
  VALUE_CHANGED: "값 다름",
  UNKNOWN: "판정 불가",
}

// 이전 성공 배포의 이미지·설정으로 복구한다 (#101). 계획을 확인한 뒤 그 계획 지문으로만 실행한다
export function RollbackDialog({
  open,
  onOpenChange,
  owner,
  repo,
  onRollbackSuccess,
}: RollbackDialogProps) {
  const [loading, setLoading] = useState(false)
  const [candidates, setCandidates] = useState<RecoveryCandidate[] | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [plan, setPlan] = useState<RecoveryPlan | null>(null)
  const [planError, setPlanError] = useState<string | null>(null)
  const [planLoading, setPlanLoading] = useState(false)
  const [recovering, setRecovering] = useState(false)
  const { toast } = useToast()

  useEffect(() => {
    if (open) {
      fetchCandidates()
    }
  }, [open, owner, repo])

  const fetchCandidates = async () => {
    try {
      setLoading(true)
      setSelectedId(null)
      setPlan(null)
      setPlanError(null)
      setCandidates(await api.getRecoveryCandidates(owner, repo))
    } catch (error) {
      setCandidates(null)
      toast({
        title: "복구 후보 조회 실패",
        description: error instanceof Error ? error.message : String(error),
        variant: "destructive",
      })
    } finally {
      setLoading(false)
    }
  }

  const selectCandidate = async (deploymentId: number) => {
    setSelectedId(deploymentId)
    setPlan(null)
    setPlanError(null)
    try {
      setPlanLoading(true)
      setPlan(await api.getRecoveryPlan(deploymentId))
    } catch (error) {
      setPlanError(error instanceof Error ? error.message : String(error))
    } finally {
      setPlanLoading(false)
    }
  }

  const handleRecover = async () => {
    if (!plan?.executable || !plan.plan_fingerprint) return
    try {
      setRecovering(true)
      const deployment = await api.recoverDeployment(plan.target_deployment_id, plan.plan_fingerprint)
      toast({
        title: "복구 배포를 요청했습니다",
        description: `배포 #${deployment.id}가 배포 #${plan.target_deployment_id}의 이미지·설정으로 진행됩니다. 결과는 배포 목록에서 확인하세요.`,
      })
      onOpenChange(false)
      onRollbackSuccess?.()
    } catch (error) {
      toast({
        title: "복구하지 못했습니다",
        description: error instanceof Error ? error.message : String(error),
        variant: "destructive",
      })
      // 계획이 바뀌었을 수 있으므로 다시 불러온다
      await selectCandidate(plan.target_deployment_id)
    } finally {
      setRecovering(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl max-h-[90vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <RotateCcw className="w-5 h-5" />
            이전 배포로 복구 - {owner}/{repo}
          </DialogTitle>
          <DialogDescription>
            이전 성공 배포를 고르면 복구 계획을 보여 줍니다. 이미지와 env 외 배포 설정을 그 배포 시점으로 되돌립니다.
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 space-y-4 overflow-y-auto">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary"></div>
              <span className="ml-4">복구 후보 조회 중...</span>
            </div>
          ) : !candidates ? (
            <div className="text-center py-8 text-muted-foreground">
              <AlertCircle className="h-12 w-12 mx-auto mb-4" />
              <p>복구 후보를 불러올 수 없습니다.</p>
            </div>
          ) : (
            <div className="border rounded-lg overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[60px] text-center">선택</TableHead>
                    <TableHead>배포</TableHead>
                    <TableHead>커밋</TableHead>
                    <TableHead>이미지</TableHead>
                    <TableHead>완료 시각</TableHead>
                    <TableHead className="text-center">설정 기록</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {candidates.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={6} className="text-center py-8 text-muted-foreground">
                        이미지가 기록된 성공 배포가 없습니다.
                      </TableCell>
                    </TableRow>
                  ) : (
                    candidates.map((candidate, idx) => (
                      <TableRow
                        key={candidate.deployment_id}
                        className={`cursor-pointer ${selectedId === candidate.deployment_id ? "bg-muted" : ""}`}
                        onClick={() => selectCandidate(candidate.deployment_id)}
                      >
                        <TableCell className="text-center">
                          <input
                            type="radio"
                            aria-label={`배포 #${candidate.deployment_id} 선택`}
                            checked={selectedId === candidate.deployment_id}
                            onChange={() => selectCandidate(candidate.deployment_id)}
                            className="cursor-pointer w-4 h-4"
                          />
                        </TableCell>
                        <TableCell className="whitespace-nowrap">
                          #{candidate.deployment_id}
                          {idx === 0 && <Badge variant="outline" className="ml-2 text-xs">최근 성공</Badge>}
                        </TableCell>
                        <TableCell className="font-mono text-xs">{candidate.commit_hash.slice(0, 7)}</TableCell>
                        <TableCell className="font-mono text-xs break-all">{candidate.image_uri}</TableCell>
                        <TableCell className="text-sm text-muted-foreground whitespace-nowrap">
                          {formatKst(candidate.finished_at)}
                        </TableCell>
                        <TableCell className="text-center">
                          {candidate.config_recorded ? (
                            <Badge variant="outline" className="text-xs">있음</Badge>
                          ) : (
                            <Badge variant="secondary" className="text-xs">없음</Badge>
                          )}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </div>
          )}

          {planLoading && <p className="text-sm text-muted-foreground">복구 계획 계산 중...</p>}
          {planError && <p className="text-sm text-destructive">복구 계획을 불러오지 못했습니다: {planError}</p>}
          {plan && (
            <div className="space-y-3 rounded-lg border p-4">
              <p className="text-sm font-semibold">복구 계획: 배포 #{plan.target_deployment_id}</p>
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                <dt className="text-muted-foreground">배포할 이미지</dt>
                <dd className="font-mono text-xs break-all">{plan.image}</dd>
                <dt className="text-muted-foreground">digest 고정</dt>
                <dd>{plan.digest_pinned ? "예 (관측한 digest로 배포)" : "아니요 (digest 기록 없음, 태그로 배포)"}</dd>
              </dl>
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">되돌릴 설정 (현재 → 대상)</p>
                {plan.config_changes.length === 0 ? (
                  <p className="text-sm">바뀌는 설정 없음</p>
                ) : (
                  <ul className="space-y-1 text-xs">
                    {plan.config_changes.map((change) => (
                      <li key={change.field} className="font-mono break-all">
                        {change.field}: {change.before ?? "없음"} → {change.after ?? "없음"}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              {plan.env_differences.length > 0 && (
                <div className="space-y-1">
                  <p className="text-sm text-muted-foreground">env 차이 (값은 기록하지 않아 되돌릴 수 없음)</p>
                  <ul className="space-y-1 text-xs">
                    {plan.env_differences.map((change) => (
                      <li key={change.field} className="font-mono">
                        {change.field.replace(/^env:/, "")}: {ENV_KIND_LABEL[change.kind]}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {!plan.executable && (
                <div className="flex gap-2 text-sm text-destructive">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                  <ul className="space-y-1">
                    {plan.blocked_reasons.map((reason) => <li key={reason}>{reason}</li>)}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>

        <DialogFooter className="mt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={recovering}>
            취소
          </Button>
          <Button onClick={handleRecover} disabled={!plan?.executable || recovering || planLoading}>
            <RotateCcw className="w-4 h-4 mr-2" />
            {recovering ? "복구 요청 중..." : "이 계획으로 복구"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
