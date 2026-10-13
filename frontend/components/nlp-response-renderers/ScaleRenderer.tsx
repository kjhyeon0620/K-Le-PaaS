import React from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { CheckCircle, XCircle, Copy, TrendingUp, TrendingDown, Minus } from 'lucide-react'
import { NLPResponse } from '@/lib/types/nlp-response'
import { cn } from '@/lib/utils'
import { copyToClipboard } from '@/lib/utils/clipboard'

interface ScaleRendererProps {
  response: NLPResponse
}

export function ScaleRenderer({ response }: ScaleRendererProps) {
  const { data, metadata } = response
  const scaleData = data?.formatted || {}

  // 타입 안전성을 위해 기본값 설정
  const repository = scaleData.repository || ''
  const old_replicas = scaleData.old_replicas || 0
  const new_replicas = scaleData.new_replicas || 0
  const status = scaleData.status || 'unknown'
  const timestamp = scaleData.timestamp || ''
  const action = scaleData.action || '스케일링'

  // metadata에서 owner/repo 추출 (표시용) - 타입 안전성 확보
  const owner = (metadata && 'owner' in metadata) ? metadata.owner : ''
  const repo = (metadata && 'repo' in metadata) ? metadata.repo : ''

  const isSuccess = status === '성공' || status === 'success'
  const replicaChange = new_replicas - old_replicas

  const getStatusIcon = () => {
    if (isSuccess) {
      return <CheckCircle className="w-4 h-4 text-green-600" />
    }
    return <XCircle className="w-4 h-4 text-red-600" />
  }

  const getStatusBadge = () => {
    if (isSuccess) {
      return (
        <Badge className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200 border-green-200">
          성공
        </Badge>
      )
    }
    return (
      <Badge variant="destructive">
        실패
      </Badge>
    )
  }

  const getReplicaChangeIcon = () => {
    if (replicaChange > 0) {
      return <TrendingUp className="w-4 h-4 text-green-600" />
    } else if (replicaChange < 0) {
      return <TrendingDown className="w-4 h-4 text-orange-600" />
    }
    return <Minus className="w-4 h-4 text-gray-600" />
  }

  const getReplicaChangeText = () => {
    if (replicaChange > 0) {
      return `+${replicaChange}개 증가`
    } else if (replicaChange < 0) {
      return `${replicaChange}개 감소`
    }
    return '변화 없음'
  }

  const getReplicaChangeColor = () => {
    if (replicaChange > 0) {
      return 'text-green-600 dark:text-green-400'
    } else if (replicaChange < 0) {
      return 'text-orange-600 dark:text-orange-400'
    }
    return 'text-gray-600 dark:text-gray-400'
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              ⚖️ 스케일링 결과
            </CardTitle>
            <CardDescription>
              {response.summary}
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            {getStatusBadge()}
            <Button
              variant="outline"
              size="sm"
              onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
            >
              <Copy className="w-4 h-4" />
            </Button>
          </div>
        </div>

        {/* 변경 요약 */}
        <div className="flex gap-4 mt-4">
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">저장소:</span>
            <span className="text-sm font-medium">{repository}</span>
          </div>
          <div className={cn("flex items-center gap-2", getReplicaChangeColor())}>
            {getReplicaChangeIcon()}
            <span className="text-sm font-medium">{getReplicaChangeText()}</span>
          </div>
        </div>
      </CardHeader>

      <CardContent>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>항목</TableHead>
                <TableHead>내용</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {/* 저장소 정보 */}
              <TableRow>
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    📦 저장소
                  </div>
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-sm">{repository}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => copyToClipboard(repository)}
                      title="저장소 이름 복사"
                    >
                      <Copy className="w-3 h-3" />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>

              {/* 레플리카 변경 */}
              <TableRow>
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    ⚖️ 레플리카 변경
                  </div>
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-3">
                    <Badge variant="outline" className="font-mono">
                      {old_replicas}
                    </Badge>
                    <span className="text-muted-foreground">→</span>
                    <Badge variant="outline" className="font-mono">
                      {new_replicas}
                    </Badge>
                    <div className={cn("flex items-center gap-1 ml-2", getReplicaChangeColor())}>
                      {getReplicaChangeIcon()}
                      <span className="text-sm font-medium">{getReplicaChangeText()}</span>
                    </div>
                  </div>
                </TableCell>
              </TableRow>

              {/* 상태 */}
              <TableRow>
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    {getStatusIcon()} 상태
                  </div>
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-2">
                    {getStatusBadge()}
                    <span className={cn(
                      "text-sm",
                      isSuccess
                        ? "text-green-600 dark:text-green-400"
                        : "text-red-600 dark:text-red-400"
                    )}>
                      {isSuccess ? '스케일링 성공' : '스케일링 실패'}
                    </span>
                  </div>
                </TableCell>
              </TableRow>

              {/* 완료 시간 */}
              {timestamp && (
                <TableRow>
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      🕒 완료 시간
                    </div>
                  </TableCell>
                  <TableCell>
                    <span className="text-sm text-muted-foreground">{timestamp}</span>
                  </TableCell>
                </TableRow>
              )}

              {/* 작업 유형 */}
              <TableRow>
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    🔧 작업 유형
                  </div>
                </TableCell>
                <TableCell>
                  <Badge variant="secondary">{action}</Badge>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}
