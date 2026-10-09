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
import { ScrollArea } from "@/components/ui/scroll-area"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { AlertCircle, CheckCircle, Clock, RotateCcw, GitCommit } from "lucide-react"
import { api, RollbackListResponse, RollbackCandidate } from "@/lib/api"
import { useToast } from "@/hooks/use-toast"
import { formatKst } from "@/lib/time"

interface RollbackDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  owner: string
  repo: string
  onRollbackSuccess?: () => void
}

export function RollbackDialog({
  open,
  onOpenChange,
  owner,
  repo,
  onRollbackSuccess,
}: RollbackDialogProps) {
  const [loading, setLoading] = useState(false)
  const [rollbackData, setRollbackData] = useState<RollbackListResponse | null>(null)
  const [selectedCommit, setSelectedCommit] = useState<RollbackCandidate | null>(null)
  const [rolling, setRolling] = useState(false)
  const { toast } = useToast()

  useEffect(() => {
    if (open) {
      fetchRollbackList()
    }
  }, [open, owner, repo])

  const fetchRollbackList = async () => {
    try {
      setLoading(true)
      const data = await api.getRollbackList(owner, repo)
      setRollbackData(data)
      setSelectedCommit(null)
    } catch (error) {
      console.error("Failed to fetch rollback list:", error)
      toast({
        title: "롤백 목록 조회 실패",
        description: String(error),
        variant: "destructive",
      })
    } finally {
      setLoading(false)
    }
  }

  const handleRollback = async () => {
    if (!selectedCommit) {
      toast({
        title: "커밋을 선택해주세요",
        description: "롤백할 커밋을 먼저 선택해야 합니다.",
        variant: "destructive",
      })
      return
    }

    try {
      setRolling(true)
      await api.rollbackToCommit(owner, repo, selectedCommit.commit_sha)

      toast({
        title: "롤백 시작",
        description: `커밋 ${selectedCommit.commit_sha_short}로 롤백을 시작했습니다.`,
      })

      onOpenChange(false)
      onRollbackSuccess?.()
    } catch (error) {
      console.error("Rollback failed:", error)
      toast({
        title: "롤백 실패",
        description: String(error),
        variant: "destructive",
      })
    } finally {
      setRolling(false)
    }
  }

  const formatTime = (isoString: string | null) => formatKst(isoString)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl max-h-[90vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <RotateCcw className="w-5 h-5" />
            배포 롤백 - {owner}/{repo}
          </DialogTitle>
          <DialogDescription>
            롤백할 버전을 선택하세요. 선택한 커밋으로 배포가 되돌려집니다.
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary"></div>
            <span className="ml-4">롤백 정보 조회 중...</span>
          </div>
        ) : !rollbackData ? (
          <div className="text-center py-8 text-muted-foreground">
            <AlertCircle className="h-12 w-12 mx-auto mb-4" />
            <p>롤백 정보를 불러올 수 없습니다.</p>
          </div>
        ) : (
          <Tabs defaultValue="versions" className="w-full">
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="versions">사용 가능한 버전</TabsTrigger>
              <TabsTrigger value="history">롤백 히스토리</TabsTrigger>
            </TabsList>

            <TabsContent value="versions" className="space-y-4">
              {/* Current Deployment State */}
              {rollbackData.current_state && (
                <div className="p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-2">
                        <CheckCircle className="w-4 h-4 text-blue-600" />
                        <span className="font-semibold text-blue-900 dark:text-blue-100">
                          현재 배포 상태
                        </span>
                        {rollbackData.current_state.is_rollback && (
                          <Badge variant="outline" className="text-xs">
                            Rollback
                          </Badge>
                        )}
                      </div>
                      <div className="text-sm space-y-1">
                        <div className="flex items-center gap-2">
                          <GitCommit className="w-3 h-3" />
                          <code className="font-mono text-xs bg-blue-100 dark:bg-blue-900 px-2 py-0.5 rounded">
                            {rollbackData.current_state.commit_sha_short}
                          </code>
                          <span className="text-muted-foreground truncate max-w-md">
                            {rollbackData.current_state.commit_message}
                          </span>
                        </div>
                        <div className="flex items-center gap-2 text-muted-foreground">
                          <Clock className="w-3 h-3" />
                          {formatTime(rollbackData.current_state.deployed_at)}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Available Versions Table */}
              <div className="border rounded-lg overflow-x-auto">
                <div className="min-w-[800px]">
                    <Table>
                      <TableHeader className="sticky top-0 bg-muted z-10">
                        <TableRow>
                          <TableHead className="w-[60px] text-center">선택</TableHead>
                          <TableHead className="w-[120px]">커밋</TableHead>
                          <TableHead className="w-[200px]">메시지</TableHead>
                          <TableHead className="w-[200px]">배포 시간</TableHead>
                          <TableHead className="w-[100px] text-center">상태</TableHead>
                        </TableRow>
                      </TableHeader>
                    <TableBody>
                    {!rollbackData.available_versions || rollbackData.available_versions.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5} className="text-center py-8 text-muted-foreground">
                          <AlertCircle className="h-8 w-8 mx-auto mb-2" />
                          <p>사용 가능한 롤백 버전이 없습니다.</p>
                        </TableCell>
                      </TableRow>
                    ) : (
                      rollbackData.available_versions.map((version, idx) => (
                        <TableRow
                          key={`${version.commit_sha}-${idx}`}
                          className={`cursor-pointer hover:bg-muted/50 transition-colors ${
                            selectedCommit?.commit_sha === version.commit_sha
                              ? "bg-blue-50 dark:bg-blue-900/20"
                              : ""
                          }`}
                          onClick={() => setSelectedCommit(version)}
                        >
                          <TableCell className="text-center">
                            <div className="flex items-center justify-center">
                              <input
                                type="radio"
                                checked={selectedCommit?.commit_sha === version.commit_sha}
                                onChange={() => setSelectedCommit(version)}
                                className="cursor-pointer w-4 h-4"
                              />
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <code className="font-mono text-xs bg-muted px-2 py-0.5 rounded whitespace-nowrap">
                                {version.commit_sha_short}
                              </code>
                              {version.is_current && (
                                <Badge variant="outline" className="text-xs whitespace-nowrap">
                                  Current
                                </Badge>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="truncate max-w-[300px]" title={version.commit_message || "메시지 없음"}>
                              {version.commit_message || "메시지 없음"}
                            </div>
                          </TableCell>
                          <TableCell className="text-sm text-muted-foreground whitespace-nowrap w-[200px]">
                            {formatTime(version.deployed_at)}
                          </TableCell>
                          <TableCell className="text-center">
                            {version.is_current ? (
                              <Badge variant="default" className="text-xs whitespace-nowrap">현재</Badge>
                            ) : (
                              <Badge variant="outline" className="text-xs whitespace-nowrap">
                                {version.steps_back}번 전
                              </Badge>
                            )}
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                    </TableBody>
                    </Table>
                </div>
              </div>

              {selectedCommit && (
                <div className="p-4 bg-muted rounded-lg">
                  <p className="text-sm font-semibold mb-2">선택된 커밋:</p>
                  <div className="flex items-center gap-2">
                    <code className="font-mono text-xs bg-background px-2 py-1 rounded">
                      {selectedCommit.commit_sha_short}
                    </code>
                    <span className="text-sm truncate">{selectedCommit.commit_message}</span>
                  </div>
                </div>
              )}
            </TabsContent>

            <TabsContent value="history" className="space-y-4">
              <div className="border rounded-lg overflow-x-auto">
                <div className="min-w-[800px]">
                    {!rollbackData.rollback_history || rollbackData.rollback_history.length === 0 ? (
                      <div className="text-center py-8 text-muted-foreground">
                        <AlertCircle className="h-8 w-8 mx-auto mb-2" />
                        <p>롤백 히스토리가 없습니다.</p>
                      </div>
                    ) : (
                      <Table>
                        <TableHeader className="sticky top-0 bg-muted z-10">
                          <TableRow>
                            <TableHead className="w-[120px]">커밋</TableHead>
                            <TableHead className="w-[200px]">메시지</TableHead>
                            <TableHead className="w-[200px]">롤백 시간</TableHead>
                            <TableHead className="w-[100px] text-center">상태</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {rollbackData.rollback_history.map((item, idx) => (
                            <TableRow key={idx} className="hover:bg-muted/50 transition-colors">
                              <TableCell>
                                <div className="flex items-center gap-2">
                                  <RotateCcw className="w-3 h-3 text-orange-500" />
                                  <code className="font-mono text-xs bg-orange-100 dark:bg-orange-900/30 px-2 py-0.5 rounded">
                                    {item.commit_sha_short}
                                  </code>
                                </div>
                              </TableCell>
                              <TableCell>
                                <div className="truncate max-w-[300px]" title={item.commit_message || "메시지 없음"}>
                                  {item.commit_message || "메시지 없음"}
                                </div>
                              </TableCell>
                              <TableCell className="text-sm text-muted-foreground whitespace-nowrap w-[200px]">
                                {formatTime(item.rolled_back_at)}
                              </TableCell>
                              <TableCell className="text-center">
                                <Badge variant="outline" className="text-xs">
                                  Rollback
                                </Badge>
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    )}
                </div>
              </div>
            </TabsContent>
          </Tabs>
        )}
        </div>

        <DialogFooter className="mt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={rolling}>
            취소
          </Button>
          <Button
            onClick={handleRollback}
            disabled={!selectedCommit || rolling || selectedCommit.is_current}
          >
            {rolling ? (
              <>
                <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></div>
                롤백 중...
              </>
            ) : (
              <>
                <RotateCcw className="w-4 h-4 mr-2" />
                롤백 실행
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
