import React from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CheckCircle, XCircle, Copy, RefreshCw, GitBranch, Globe, Clock, Sparkles } from 'lucide-react'
import { NLPResponse } from '@/lib/types/nlp-response'
import { cn } from '@/lib/utils'
import { copyToClipboard } from '@/lib/utils/clipboard'

interface RestartRendererProps {
  response: NLPResponse
}

export function RestartRenderer({ response }: RestartRendererProps) {
  const { data, metadata } = response
  const restartData = data?.formatted || {}

  // 타입 안전성을 위해 기본값 설정
  const repository = restartData.repository || ''
  const deployment = restartData.deployment || ''
  const owner = restartData.owner || ''
  const repo = restartData.repo || ''
  const status = restartData.status || 'unknown'
  const message = restartData.message || ''
  const namespace = restartData.namespace || 'default'
  const timestamp = restartData.timestamp || ''

  const isSuccess = status === 'success' || status === '성공'
  const displayName = repository || deployment

  return (
    <Card className="w-full border">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={cn(
              "p-2 rounded-lg",
              isSuccess ? "bg-green-100 dark:bg-green-900/30" : "bg-red-100 dark:bg-red-900/30"
            )}>
              {isSuccess ? (
                <CheckCircle className="w-5 h-5 text-green-600 dark:text-green-400" />
              ) : (
                <XCircle className="w-5 h-5 text-red-600 dark:text-red-400" />
              )}
            </div>
            <div>
              <CardTitle className="text-base flex items-center gap-2">
                <RefreshCw className="w-4 h-4" />
                재시작
              </CardTitle>
              <CardDescription className="text-xs">
                {response.summary}
              </CardDescription>
            </div>
          </div>
          
          <Button
            variant="ghost"
            size="sm"
            onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
            className="h-8 w-8 p-0"
          >
            <Copy className="h-4 w-4" />
          </Button>
        </div>
      </CardHeader>

      <CardContent className="p-4 pt-0">
        {/* 상태 메시지 */}
        <div className={cn(
          "rounded-lg p-3 mb-3",
          isSuccess 
            ? "bg-green-50 dark:bg-green-950/20 border border-green-200 dark:border-green-800"
            : "bg-red-50 dark:bg-red-950/20 border border-red-200 dark:border-red-800"
        )}>
          <p className={cn(
            "text-sm font-medium",
            isSuccess 
              ? "text-green-800 dark:text-green-200" 
              : "text-red-800 dark:text-red-200"
          )}>
            {isSuccess ? 'Rolling Restart 완료' : message || '재시작 실패'}
          </p>
        </div>

        {/* 정보 그리드 */}
        <div className="grid grid-cols-2 gap-2 mb-3">
          {/* 저장소 */}
          <div className="rounded-md border bg-muted/30 p-2">
            <div className="flex items-center gap-2">
              <GitBranch className="w-3.5 h-3.5 text-muted-foreground" />
              <div className="flex-1 min-w-0">
                <p className="text-xs text-muted-foreground">저장소</p>
                <p className="font-mono text-xs font-medium truncate">{displayName || 'N/A'}</p>
              </div>
            </div>
          </div>

          {/* Deployment */}
          {deployment && (
            <div className="rounded-md border bg-muted/30 p-2">
              <div className="flex items-center gap-2">
                <RefreshCw className="w-3.5 h-3.5 text-muted-foreground" />
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-muted-foreground">Deployment</p>
                  <p className="font-mono text-xs font-medium truncate">{deployment}</p>
                </div>
              </div>
            </div>
          )}

          {/* 네임스페이스 */}
          <div className="rounded-md border bg-muted/30 p-2">
            <div className="flex items-center gap-2">
              <Globe className="w-3.5 h-3.5 text-muted-foreground" />
              <div className="flex-1 min-w-0">
                <p className="text-xs text-muted-foreground">네임스페이스</p>
                <p className="font-mono text-xs font-medium">{namespace}</p>
              </div>
            </div>
          </div>

          {/* 실행 시간 */}
          {timestamp && (
            <div className="rounded-md border bg-muted/30 p-2">
              <div className="flex items-center gap-2">
                <Clock className="w-3.5 h-3.5 text-muted-foreground" />
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-muted-foreground">실행 시간</p>
                  <p className="font-mono text-xs font-medium">{timestamp}</p>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Rolling Restart 설명 - 컴팩트하게 */}
        {isSuccess && (
          <div className="rounded-md border bg-muted/20 p-3">
            <div className="flex items-start gap-2">
              <RefreshCw className="w-4 h-4 text-muted-foreground mt-0.5" />
              <div className="flex-1">
                <p className="text-xs font-medium text-muted-foreground mb-1.5">Rolling Restart</p>
                <p className="text-xs text-muted-foreground">
                  Zero-downtime deployment 방식으로 재시작되었습니다.
                </p>
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

