"use client"

import { useEffect, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Slider } from "@/components/ui/slider"
import { Badge } from "@/components/ui/badge"
import { Scale, AlertCircle, TrendingUp, TrendingDown } from "lucide-react"
import { api, type ReplicaStatus } from "@/lib/api"
import { useToast } from "@/hooks/use-toast"

interface ScaleDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  owner: string
  repo: string
  repositoryId: number
  onScaleSuccess?: () => void
}

const MIN_REPLICAS = 1
const MAX_REPLICAS = 10

export function ScaleDialog({
  open,
  onOpenChange,
  owner,
  repo,
  repositoryId,
  onScaleSuccess,
}: ScaleDialogProps) {
  const [replicaStatus, setReplicaStatus] = useState<ReplicaStatus | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [targetReplicas, setTargetReplicas] = useState(MIN_REPLICAS)
  const [scaling, setScaling] = useState(false)
  const { toast } = useToast()

  // 현재 값은 클러스터 관측값이다. 저장된 설정값으로 채우지 않고, 관측할 수 없으면 null로 둔다 (#90)
  const currentReplicas = replicaStatus?.observation === "AVAILABLE" ? replicaStatus.desired : null
  const unobservedReason = loadError ?? replicaStatus?.observation_message ?? null

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setReplicaStatus(null)
    setLoadError(null)
    api.getReplicaStatus(repositoryId)
      .then((status) => {
        if (cancelled) return
        setReplicaStatus(status)
        setTargetReplicas(status.observation === "AVAILABLE" && status.desired != null ? status.desired : MIN_REPLICAS)
      })
      .catch((error) => {
        if (!cancelled) setLoadError(`현재 레플리카를 조회하지 못했습니다: ${String(error)}`)
      })
    return () => { cancelled = true }
  }, [open, repositoryId])

  const handleScaleChange = (value: number[]) => {
    setTargetReplicas(value[0])
  }

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = parseInt(e.target.value, 10)
    if (!isNaN(value) && value >= MIN_REPLICAS && value <= MAX_REPLICAS) {
      setTargetReplicas(value)
    }
  }

  const handleScale = async () => {
    if (currentReplicas != null && targetReplicas === currentReplicas) {
      toast({
        title: "변경사항 없음",
        description: "현재 레플리카 개수와 동일합니다.",
        variant: "destructive",
      })
      return
    }

    try {
      setScaling(true)
      await api.scaleDeployment(owner, repo, targetReplicas)

      toast({
        title: "스케일링 시작",
        description: currentReplicas == null
          ? `레플리카를 ${targetReplicas}개로 조정합니다.`
          : `레플리카를 ${currentReplicas}개에서 ${targetReplicas}개로 조정합니다.`,
      })

      onOpenChange(false)
      onScaleSuccess?.()
    } catch (error) {
      console.error("Scaling failed:", error)
      toast({
        title: "스케일링 실패",
        description: String(error),
        variant: "destructive",
      })
    } finally {
      setScaling(false)
    }
  }

  const getScaleDirection = () => {
    if (currentReplicas == null) {
      return { icon: Scale, text: "현재 값 확인 불가", color: "text-gray-600" }
    }
    if (targetReplicas > currentReplicas) {
      return { icon: TrendingUp, text: "Scale Up", color: "text-green-600" }
    } else if (targetReplicas < currentReplicas) {
      return { icon: TrendingDown, text: "Scale Down", color: "text-orange-600" }
    }
    return { icon: Scale, text: "No Change", color: "text-gray-600" }
  }

  const scaleDirection = getScaleDirection()
  const ScaleIcon = scaleDirection.icon

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Scale className="w-5 h-5" />
            배포 스케일링 - {owner}/{repo}
          </DialogTitle>
          <DialogDescription>
            Pod 레플리카 개수를 조정합니다. ({MIN_REPLICAS}-{MAX_REPLICAS}개)
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-6 py-4">
          {/* Current Status */}
          <div className="p-4 bg-muted rounded-lg">
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-semibold">현재 레플리카</span>
              <Badge variant="outline" className="text-lg font-mono">
                {currentReplicas != null ? `${currentReplicas}개` : replicaStatus || loadError ? "확인 불가" : "조회 중"}
              </Badge>
            </div>
            {currentReplicas != null && replicaStatus && (
              <p className="text-xs text-muted-foreground">
                Ready {replicaStatus.ready} · Available {replicaStatus.available} · Updated {replicaStatus.updated}
              </p>
            )}
            {currentReplicas == null && unobservedReason && (
              <p className="text-xs text-muted-foreground">{unobservedReason}</p>
            )}
          </div>

          {/* Target Replicas Slider */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label htmlFor="replicas-slider" className="text-sm font-semibold">
                목표 레플리카
              </Label>
              <div className="flex items-center gap-2">
                <Input
                  id="replicas-input"
                  type="number"
                  min={MIN_REPLICAS}
                  max={MAX_REPLICAS}
                  value={targetReplicas}
                  onChange={handleInputChange}
                  className="w-20 h-8 text-center font-mono"
                />
                <span className="text-sm text-muted-foreground">개</span>
              </div>
            </div>

            <Slider
              id="replicas-slider"
              min={MIN_REPLICAS}
              max={MAX_REPLICAS}
              step={1}
              value={[targetReplicas]}
              onValueChange={handleScaleChange}
              className="w-full"
            />

            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{MIN_REPLICAS}</span>
              <span>5</span>
              <span>{MAX_REPLICAS}</span>
            </div>
          </div>

          {/* Scale Direction Indicator */}
          {currentReplicas != null && targetReplicas !== currentReplicas && (
            <div className={`p-4 rounded-lg border ${
              targetReplicas > currentReplicas
                ? "bg-green-50 border-green-200 dark:bg-green-900/20 dark:border-green-800"
                : "bg-orange-50 border-orange-200 dark:bg-orange-900/20 dark:border-orange-800"
            }`}>
              <div className="flex items-center gap-3">
                <ScaleIcon className={`w-5 h-5 ${scaleDirection.color}`} />
                <div className="flex-1">
                  <p className={`text-sm font-semibold ${scaleDirection.color}`}>
                    {scaleDirection.text}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {currentReplicas}개 → {targetReplicas}개
                    {targetReplicas > currentReplicas ? " (+)" : " (-)"}
                    {Math.abs(targetReplicas - currentReplicas)}개
                  </p>
                </div>
              </div>
            </div>
          )}

          <p className="text-xs text-muted-foreground">
            스케일은 지금 실행 중인 앱에만 적용됩니다. 다음 배포 때 레플리카는 배포 설정의 min_replicas로 바뀝니다.
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={scaling}>
            취소
          </Button>
          <Button
            onClick={handleScale}
            disabled={scaling || (!replicaStatus && !loadError) || targetReplicas === currentReplicas}
          >
            {scaling ? (
              <>
                <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></div>
                스케일링 중...
              </>
            ) : (
              <>
                <Scale className="w-4 h-4 mr-2" />
                스케일링 실행
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
