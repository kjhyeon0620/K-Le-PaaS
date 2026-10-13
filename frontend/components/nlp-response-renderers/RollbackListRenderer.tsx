"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { 
  Copy, 
  GitBranch, 
  RotateCcw, 
  AlertCircle
} from "lucide-react"
import { RollbackListResponse, RollbackListData, RollbackVersion, RollbackCurrent } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { cn } from "@/lib/utils"

interface RollbackListRendererProps {
  response: RollbackListResponse
  onRollbackClick?: (version: RollbackVersion) => void
}

export function RollbackListRenderer({ response, onRollbackClick }: RollbackListRendererProps) {
  const { data, metadata } = response
  const rollbackData = data.formatted as RollbackListData

  const formatCommitHash = (commit: string) => {
    return commit.length > 7 ? commit.substring(0, 7) : commit
  }

  const renderCurrentVersion = (current: RollbackCurrent) => (
    <div className="bg-muted/50 p-3 rounded-lg">
      <div className="flex items-center gap-2 mb-2">
        <code className="text-sm font-mono bg-background px-2 py-1 rounded">
          {formatCommitHash(current.commit)}
        </code>
        <Badge variant="outline" className="text-xs">
          {current.date}
        </Badge>
        {current.is_rollback && (
          <Badge variant="destructive" className="text-xs">
            롤백됨
          </Badge>
        )}
      </div>
      <p className="text-sm text-muted-foreground">
        {current.message}
      </p>
      {current.deployment_id && (
        <p className="text-xs text-muted-foreground mt-1">
          배포 ID: {current.deployment_id}
        </p>
      )}
    </div>
  )


  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-start justify-between mb-3">
          <div className="flex-1">
            <CardTitle className="flex items-center gap-2 mb-2">
              <GitBranch className="w-5 h-5" />
              배포 버전 관리
            </CardTitle>
            <CardDescription className="text-sm">
              <span className="text-muted-foreground">{metadata.owner}/{metadata.repo}</span> · {response.summary}
            </CardDescription>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
            className="shrink-0"
          >
            <Copy className="w-4 h-4 mr-2" />
            복사
          </Button>
        </div>
        
        {/* 통계 요약 */}
        <div className="flex items-center gap-2">
          <Badge variant="secondary" className="text-xs">
            롤백 가능: {metadata.total_available}개
          </Badge>
          <Badge variant="destructive" className="text-xs">
            롤백 이력: {metadata.total_rollbacks}개
          </Badge>
        </div>
      </CardHeader>
      
      <CardContent>
        {/* 현재 버전 */}
        <div className="mb-4">
          <div className="flex items-center gap-2 mb-2">
            <GitBranch className="w-4 h-4" />
            <span className="font-medium">현재 버전</span>
          </div>
          {renderCurrentVersion(rollbackData.current)}
        </div>

        <Separator className="my-4" />

        {/* 롤백 가능한 버전들 테이블 */}
        <div>
          <div className="flex items-center gap-2 mb-3">
            <RotateCcw className="w-4 h-4" />
            <span className="font-medium">롤백 가능한 버전들 ({rollbackData.versions.length}개)</span>
          </div>
          
          {rollbackData.versions.length === 0 ? (
            <div className="p-6 border border-dashed rounded-lg text-center bg-muted/20">
              <RotateCcw className="w-8 h-8 mx-auto mb-2 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                롤백 가능한 버전이 없습니다.
              </p>
              <p className="text-xs text-muted-foreground mt-1">
                이전 배포 이력이 없어 롤백할 수 없습니다.
              </p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>버전</TableHead>
                  <TableHead>커밋</TableHead>
                  <TableHead>메시지</TableHead>
                  <TableHead>배포 시간</TableHead>
                  <TableHead>액션</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rollbackData.versions.map((version, index) => (
                  <TableRow key={`${version.commit}-${index}`}>
                    <TableCell className="font-medium">
                      {version.steps_back === 0 ? "바로 이전 버전" : `${version.steps_back}번 전 버전`}
                    </TableCell>
                    <TableCell>
                      <code className="text-sm font-mono bg-muted px-2 py-1 rounded">
                        {formatCommitHash(version.commit)}
                      </code>
                    </TableCell>
                    <TableCell className="max-w-xs truncate">
                      {version.message}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="text-xs">
                        {version.date}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        {version.can_rollback && onRollbackClick && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => onRollbackClick(version)}
                            className="text-xs"
                          >
                            <RotateCcw className="w-3 h-3 mr-1" />
                            롤백
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => copyToClipboard(version.commit)}
                        >
                          <Copy className="w-3 h-3" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>

        {/* 롤백 히스토리 */}
        {rollbackData.history.length > 0 && (
          <>
            <Separator className="my-4" />
            <div>
              <div className="flex items-center gap-2 mb-3">
                <AlertCircle className="w-4 h-4" />
                <span className="font-medium">롤백 히스토리 ({rollbackData.history.length}개)</span>
              </div>
              
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>커밋</TableHead>
                    <TableHead>메시지</TableHead>
                    <TableHead>롤백 시간</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rollbackData.history.map((item, index) => (
                    <TableRow key={`history-${index}`}>
                      <TableCell>
                        <code className="text-sm font-mono bg-muted px-2 py-1 rounded">
                          {formatCommitHash(item.commit)}
                        </code>
                      </TableCell>
                      <TableCell className="max-w-xs truncate">
                        {item.message}
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="text-xs">
                          {item.date}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}
