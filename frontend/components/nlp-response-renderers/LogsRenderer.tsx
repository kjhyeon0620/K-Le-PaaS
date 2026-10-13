"use client"

import React, { useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, FileText, Download, RefreshCw } from "lucide-react"
import { LogsResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface LogsRendererProps {
  response: LogsResponse
}

export function LogsRenderer({ response }: LogsRendererProps) {
  const { data } = response
  const formatted = data.formatted || {}
  const pod_name = formatted.pod_name || ""
  const namespace = formatted.namespace || "default"
  const logs = formatted.log_lines || []
  const total_lines = formatted.total_lines || 0
  const [showAll, setShowAll] = useState(false)


  const downloadLogs = () => {
    const logText = logs.join('\n')
    const blob = new Blob([logText], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${pod_name}-logs.txt`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const displayedLogs = showAll ? logs : logs.slice(-50) // 최근 50줄만 표시

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <FileText className="w-5 h-5" />
              Pod 로그
            </CardTitle>
            <CardDescription>
              {response.message}
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => copyToClipboard(logs.join('\n'))}
            >
              <Copy className="w-4 h-4 mr-2" />
              복사
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={downloadLogs}
            >
              <Download className="w-4 h-4 mr-2" />
              다운로드
            </Button>
          </div>
        </div>
        
        <div className="flex items-center gap-2 mt-4">
          <Badge variant="outline">Pod: {pod_name}</Badge>
          <Badge variant="outline">네임스페이스: {namespace}</Badge>
          <Badge variant="default">총 {total_lines}줄</Badge>
        </div>
      </CardHeader>
      
      <CardContent>
        {logs.length === 0 ? (
          <p className="text-muted-foreground">로그가 없습니다.</p>
        ) : (
          <div className="space-y-4">
            {/* 로그 컨트롤 */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">
                  {showAll ? `전체 ${total_lines}줄 표시` : `최근 ${displayedLogs.length}줄 표시`}
                </span>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowAll(!showAll)}
              >
                <RefreshCw className="w-4 h-4 mr-2" />
                {showAll ? '최근만 보기' : '전체 보기'}
              </Button>
            </div>

            {/* 로그 내용 */}
            <div className="bg-black text-green-400 p-4 rounded-lg font-mono text-sm overflow-x-auto max-h-96 overflow-y-auto">
              {displayedLogs.map((log, index) => (
                <div key={index} className="whitespace-pre-wrap">
                  {log}
                </div>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
