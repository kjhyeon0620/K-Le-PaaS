"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Button } from "@/components/ui/button"
import { Copy, RefreshCw, AlertCircle, CheckCircle, Clock } from "lucide-react"
import { PodListResponse, PodInfo } from "@/lib/types/nlp-response"
import { cn } from "@/lib/utils"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface PodListRendererProps {
  response: PodListResponse
}

export function PodListRenderer({ response }: PodListRendererProps) {
  const { data, metadata } = response
  const pods = data.formatted as PodInfo[]
  
  const getStatusIcon = (status: string) => {
    switch (status.toLowerCase()) {
      case "running":
        return <CheckCircle className="w-4 h-4 text-green-600" />
      case "pending":
        return <Clock className="w-4 h-4 text-yellow-600" />
      case "failed":
      case "error":
        return <AlertCircle className="w-4 h-4 text-red-600" />
      default:
        return <AlertCircle className="w-4 h-4 text-gray-600" />
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
              <RefreshCw className="w-5 h-5" />
              Pod 목록
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
        
        {/* 통계 요약 */}
        <div className="flex gap-4 mt-4">
          <div className="flex items-center gap-2">
            <Badge variant="default" className="bg-green-100 text-green-800">
              실행 중: {metadata.running}
            </Badge>
            <Badge variant="secondary" className="bg-yellow-100 text-yellow-800">
              대기: {metadata.pending}
            </Badge>
            <Badge variant="destructive" className="bg-red-100 text-red-800">
              실패: {metadata.failed}
            </Badge>
          </div>
          <div className="text-sm text-muted-foreground">
            네임스페이스: {metadata.namespace}
          </div>
        </div>
      </CardHeader>
      
      <CardContent>
        {pods.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            Pod가 없습니다.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>상태</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>준비 상태</TableHead>
                  <TableHead>재시작</TableHead>
                  <TableHead>나이</TableHead>
                  <TableHead>노드</TableHead>
                  <TableHead>네임스페이스</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pods.map((pod, index) => (
                  <TableRow key={`${pod.name}-${index}`}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        {getStatusIcon(pod.status)}
                        <Badge variant={getStatusBadgeVariant(pod.status)}>
                          {pod.status}
                        </Badge>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-sm text-foreground">
                          {pod.name}
                        </span>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => copyToClipboard(pod.name)}
                          title="Pod 이름 복사"
                        >
                          <Copy className="w-3 h-3" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => copyToClipboard(`kubectl logs ${pod.name} -n ${pod.namespace}`)}
                          title="로그 조회 명령어 복사"
                        >
                          <RefreshCw className="w-3 h-3" />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge 
                        variant={pod.ready === "1/1" ? "default" : "secondary"}
                        className={cn(
                          pod.ready === "1/1" 
                            ? "bg-green-100 text-green-800" 
                            : "bg-yellow-100 text-yellow-800"
                        )}
                      >
                        {pod.ready}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <span className={cn(
                        "text-sm",
                        pod.restarts > 0 ? "text-red-600 font-medium" : "text-muted-foreground"
                      )}>
                        {pod.restarts}회
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm text-muted-foreground">
                        {pod.age}
                      </span>
                    </TableCell>
                    <TableCell>
                      <code className="text-xs bg-muted px-2 py-1 rounded">
                        {pod.node}
                      </code>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">
                        {pod.namespace}
                      </Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
