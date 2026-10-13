"use client"

import React, { useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, Server, CheckCircle, AlertCircle, Clock, HelpCircle, List } from "lucide-react"
import { PodStatusResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface PodStatusRendererProps {
  response: PodStatusResponse
}

export function PodStatusRenderer({ response }: PodStatusRendererProps) {
  const { data, metadata } = response
  const podStatusData = data.formatted
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

  const toggle = (name: string) => {
    setExpanded(prev => ({ ...prev, [name]: !prev[name] }))
  }

  const truncate = (text: string, len = 140) => {
    if (!text) return ""
    return text.length > len ? `${text.slice(0, len)}…` : text
  }
  
  // 에러 상태 처리 - 개선된 UI
  if ((podStatusData as any).error) {
    const errorMessage = (podStatusData as any).message || ""
    const lines = errorMessage.split('\n').filter((line: string) => line.trim())
    
    return (
      <Card className="w-full border-red-200 dark:border-red-800 bg-red-50/50 dark:bg-red-950/20">
        <CardHeader>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900 flex items-center justify-center flex-shrink-0">
              <AlertCircle className="w-5 h-5 text-red-600 dark:text-red-400" />
            </div>
            <div className="flex-1">
              <CardTitle className="text-lg text-red-700 dark:text-red-300">
                Pod를 찾을 수 없습니다
              </CardTitle>
              <CardDescription className="text-red-600 dark:text-red-400 mt-1">
                요청한 리소스를 찾을 수 없어요
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="pt-0">
          <div className="space-y-3">
            {/* 오류 메시지 */}
            <div className="p-4 bg-white dark:bg-gray-900 rounded-lg border border-red-200 dark:border-red-800">
              <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed">
                {lines[0]}
              </p>
            </div>

            {/* 도움말 섹션 */}
            {lines.length > 1 && (
              <div className="space-y-2">
                <div className="flex items-center gap-2 text-sm font-medium text-gray-700 dark:text-gray-300">
                  <HelpCircle className="w-4 h-4" />
                  <span>다음 명령어로 확인해보세요:</span>
                </div>
                <div className="space-y-1.5">
                  {(lines.slice(-1)).map((line: string, index: number) => (
                    <div
                      key={index}
                      className="flex items-center gap-2 p-3 bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 hover:border-blue-300 dark:hover:border-blue-700 transition-colors cursor-pointer group"
                      onClick={() => {
                        // 클릭 시 해당 명령어를 복사
                        const command = line.replace(/^[-*]\s*/, '').replace(/['"]/g, '')
                        navigator.clipboard.writeText(command)
                      }}
                    >
                      <List className="w-4 h-4 text-blue-500 flex-shrink-0" />
                      <code className="text-sm text-gray-700 dark:text-gray-300 group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">
                        {line.replace(/^[-*]\s*/, '')}
                      </code>
                      <span className="text-xs text-gray-500 dark:text-gray-400 group-hover:text-blue-500 ml-auto">
                        클릭하여 복사
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    )
  }
  
  const getStatusIcon = (phase: string) => {
    switch (phase.toLowerCase()) {
      case "running":
        return <CheckCircle className="w-4 h-4 text-green-600" />
      case "pending":
        return <Clock className="w-4 h-4 text-yellow-600" />
      case "failed":
        return <AlertCircle className="w-4 h-4 text-red-600" />
      default:
        return <AlertCircle className="w-4 h-4 text-gray-600" />
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Server className="w-5 h-5" />
              Pod 상태
            </CardTitle>
            <CardDescription>
              {response.summary}
            </CardDescription>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
          >
            <Copy className="w-4 h-4 mr-2" />
            복사
          </Button>
        </div>
      </CardHeader>
      
      <CardContent>
        <div className="space-y-4">
          {/* 통계 */}
          <div className="grid grid-cols-3 gap-4">
            <div className="p-4 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800">
              <div className="flex items-center gap-2 text-green-700 dark:text-green-300">
                <CheckCircle className="w-4 h-4" />
                <span className="text-sm font-medium">실행 중</span>
              </div>
              <p className="text-2xl font-bold text-green-800 dark:text-green-200 mt-1">
                {podStatusData.running}
              </p>
            </div>
            <div className="p-4 bg-yellow-50 dark:bg-yellow-950/20 rounded-lg border border-yellow-200 dark:border-yellow-800">
              <div className="flex items-center gap-2 text-yellow-700 dark:text-yellow-300">
                <Clock className="w-4 h-4" />
                <span className="text-sm font-medium">대기</span>
              </div>
              <p className="text-2xl font-bold text-yellow-800 dark:text-yellow-200 mt-1">
                {podStatusData.pending}
              </p>
            </div>
            <div className="p-4 bg-red-50 dark:bg-red-950/20 rounded-lg border border-red-200 dark:border-red-800">
              <div className="flex items-center gap-2 text-red-700 dark:text-red-300">
                <AlertCircle className="w-4 h-4" />
                <span className="text-sm font-medium">실패</span>
              </div>
              <p className="text-2xl font-bold text-red-800 dark:text-red-200 mt-1">
                {podStatusData.failed}
              </p>
            </div>
          </div>

          {/* Pod 목록 */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold">Pod 목록 ({podStatusData.total_pods}개)</h3>
              <span className="text-xs text-muted-foreground">
                라벨: {podStatusData.label_selector}
              </span>
            </div>
            
            <div className="space-y-2">
              {podStatusData.pods.map((pod, index) => (
                <div
                  key={index}
                  className="p-4 border rounded-lg hover:bg-muted/50 transition-colors"
                >
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-2">
                        {getStatusIcon(pod.phase)}
                        <code className="text-sm font-mono">{pod.name}</code>
                        <Badge variant={pod.phase === "Running" ? "default" : "secondary"}>
                          {pod.phase}
                        </Badge>
                      </div>
                      <div className="grid grid-cols-3 gap-4 text-sm text-muted-foreground">
                        <div>
                          <span className="font-medium">Ready:</span> {pod.ready}
                        </div>
                        <div>
                          <span className="font-medium">Restarts:</span> {pod.restarts}
                        </div>
                        <div>
                          <span className="font-medium">Node:</span> {pod.node}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* 건강 상태 */}
          {metadata.is_healthy && (
            <div className="flex items-center gap-2 p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800">
              <CheckCircle className="w-4 h-4 text-green-600" />
              <span className="text-sm text-green-800 dark:text-green-200 font-medium">
                모든 Pod가 정상적으로 실행 중입니다
              </span>
            </div>
          )}

          {!metadata.is_healthy && (
            <div className="flex items-center gap-2 p-3 bg-red-50 dark:bg-red-950/20 rounded-lg border border-red-200 dark:border-red-800">
              <AlertCircle className="w-4 h-4 text-red-600" />
              <span className="text-sm text-red-800 dark:text-red-200 font-medium">
                일부 Pod에 문제가 있습니다
              </span>
            </div>
          )}

          {/* 문제 있는 Pod 상세 사유 박스 (요약 + 접기/펼치기) */}
          {!metadata.is_healthy && Array.isArray((podStatusData as any).problem_pods) && (podStatusData as any).problem_pods.length > 0 && (
            <div className="p-4 mt-2 bg-red-50 dark:bg-red-950/20 rounded-lg border border-red-200 dark:border-red-800">
              <h3 className="font-semibold text-red-800 dark:text-red-200 mb-2">문제 상세</h3>
              <div className="space-y-2">
                {(podStatusData as any).problem_pods.map((p: any, idx: number) => {
                  const isOpen = !!expanded[p.name]
                  const summary = truncate(p.problem_message || "", 160)
                  return (
                    <div key={idx} className="p-3 bg-white dark:bg-gray-900 rounded border border-red-100 dark:border-red-900">
                      <div className="flex items-start gap-2">
                        <AlertCircle className="w-4 h-4 text-red-600 mt-0.5" />
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <code className="text-sm font-mono break-all">{p.name}</code>
                            {p.problem_reason && (
                              <Badge variant="destructive">{p.problem_reason}</Badge>
                            )}
                            <Button
                              variant="ghost"
                              size="sm"
                              className="ml-auto h-7 px-2"
                              onClick={() => toggle(p.name)}
                            >
                              {isOpen ? "접기" : "자세히"}
                            </Button>
                          </div>
                          {!isOpen && p.problem_message && (
                            <p className="text-xs text-red-700 dark:text-red-300 mt-1 break-words">
                              {summary}
                            </p>
                          )}
                          {isOpen && (
                            <div className="mt-2 space-y-1">
                              {p.problem_message && (
                                <pre className="text-xs text-red-700 dark:text-red-300 whitespace-pre-wrap break-words">{p.problem_message}</pre>
                              )}
                              {p.labels && (
                                <div className="text-[10px] text-muted-foreground">labels: {JSON.stringify(p.labels)}</div>
                              )}
                              {p.creation_timestamp && (
                                <div className="text-[10px] text-muted-foreground">created: {p.creation_timestamp}</div>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
