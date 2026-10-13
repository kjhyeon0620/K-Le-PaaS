"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, Activity, CheckCircle, AlertCircle, Clock, Server } from "lucide-react"
import { StatusResponse, StatusData } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { cn } from "@/lib/utils"

interface StatusRendererProps {
  response: StatusResponse
}

export function StatusRenderer({ response }: StatusRendererProps) {
  const { data, metadata } = response
  const statusData = data.formatted as StatusData
  
  const getStatusIcon = (status: string) => {
    switch (status.toLowerCase()) {
      case "running":
        return <CheckCircle className="w-5 h-5 text-green-600" />
      case "pending":
        return <Clock className="w-5 h-5 text-yellow-600" />
      case "failed":
      case "error":
        return <AlertCircle className="w-5 h-5 text-red-600" />
      default:
        return <AlertCircle className="w-5 h-5 text-gray-600" />
    }
  }

  const getStatusBadgeVariant = (status: string) => {
    switch (status.toLowerCase()) {
      case "running":
        return "default" as const
      case "pending":
        return "secondary" as const
      case "failed":
      case "error":
        return "destructive" as const
      default:
        return "outline" as const
    }
  }


  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Activity className="w-5 h-5" />
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
          {/* 상태 카드 */}
          <div className="flex items-center gap-4 p-4 bg-muted/50 rounded-lg">
            {getStatusIcon(statusData.status)}
            <div className="flex-1">
              <div className="flex items-center gap-2 mb-1">
                <code className="text-lg font-mono bg-background px-2 py-1 rounded">
                  {statusData.name}
                </code>
                <Badge variant={getStatusBadgeVariant(statusData.status)}>
                  {statusData.status}
                </Badge>
              </div>
              <p className="text-sm text-muted-foreground">
                네임스페이스: {statusData.namespace}
              </p>
            </div>
          </div>

          {/* 상세 정보 */}
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">준비 상태</span>
                <Badge 
                  variant={statusData.ready === "1/1" ? "default" : "secondary"}
                  className={cn(
                    statusData.ready === "1/1" 
                      ? "bg-green-100 text-green-800" 
                      : "bg-yellow-100 text-yellow-800"
                  )}
                >
                  {statusData.ready}
                </Badge>
              </div>
              
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">재시작 횟수</span>
                <span className={cn(
                  "text-sm font-mono",
                  statusData.restarts > 0 ? "text-red-600 font-medium" : "text-muted-foreground"
                )}>
                  {statusData.restarts}회
                </span>
              </div>
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">실행 시간</span>
                <span className="text-sm text-muted-foreground">
                  {statusData.age}
                </span>
              </div>
              
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">노드</span>
                <div className="flex items-center gap-1">
                  <Server className="w-3 h-3 text-muted-foreground" />
                  <code className="text-xs bg-muted px-2 py-1 rounded">
                    {statusData.node}
                  </code>
                </div>
              </div>
            </div>
          </div>

          {/* 건강 상태 표시 */}
          {metadata.is_healthy && (
            <div className="flex items-center gap-2 p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800">
              <CheckCircle className="w-4 h-4 text-green-600" />
              <span className="text-sm text-green-800 dark:text-green-200 font-medium">
                Pod가 정상적으로 실행 중입니다
              </span>
            </div>
          )}

          {!metadata.is_healthy && (
            <div className="flex items-center gap-2 p-3 bg-red-50 dark:bg-red-950/20 rounded-lg border border-red-200 dark:border-red-800">
              <AlertCircle className="w-4 h-4 text-red-600" />
              <span className="text-sm text-red-800 dark:text-red-200 font-medium">
                Pod에 문제가 있습니다. 로그를 확인해보세요.
              </span>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
