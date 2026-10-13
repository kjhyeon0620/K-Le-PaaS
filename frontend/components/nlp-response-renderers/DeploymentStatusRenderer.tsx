"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, Layers, CheckCircle, AlertCircle, Server, TrendingUp, HelpCircle, List } from "lucide-react"
import { DeploymentStatusResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface DeploymentStatusRendererProps {
  response: DeploymentStatusResponse
}

export function DeploymentStatusRenderer({ response }: DeploymentStatusRendererProps) {
  const { data, metadata } = response
  const deploymentData = data.formatted
  
  // 에러 상태 처리 - 개선된 UI
  if ((deploymentData as any).error) {
    const errorMessage = (deploymentData as any).message || ""
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
                Deployment를 찾을 수 없습니다
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
                  {lines.slice(1).map((line: string, index: number) => (
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
        return <AlertCircle className="w-4 h-4 text-yellow-600" />
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
              <Layers className="w-5 h-5" />
              Deployment 상태
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
          {/* Deployment 정보 */}
          <div className="p-4 bg-muted/50 rounded-lg">
            <div className="flex items-center gap-2 mb-2">
              <code className="text-lg font-mono bg-background px-2 py-1 rounded">
                {deploymentData.name}
              </code>
            </div>
            <p className="text-sm text-muted-foreground">
              네임스페이스: {deploymentData.namespace}
            </p>
          </div>

          {/* Replicas 정보 */}
          <div className="grid grid-cols-4 gap-4">
            <div className="p-4 border rounded-lg text-center">
              <div className="flex items-center justify-center gap-2 mb-2">
                <TrendingUp className="w-4 h-4 text-muted-foreground" />
                <span className="text-sm font-medium">Desired</span>
              </div>
              <p className="text-2xl font-bold">{deploymentData.replicas.desired}</p>
            </div>
            
            <div className="p-4 border rounded-lg text-center">
              <span className="text-sm font-medium">Current</span>
              <p className="text-2xl font-bold">{deploymentData.replicas.current}</p>
            </div>
            
            <div className="p-4 border rounded-lg text-center">
              <span className="text-sm font-medium">Ready</span>
              <p className="text-2xl font-bold text-green-600">{deploymentData.replicas.ready}</p>
            </div>
            
            <div className="p-4 border rounded-lg text-center">
              <span className="text-sm font-medium">Available</span>
              <p className="text-2xl font-bold text-green-600">{deploymentData.replicas.available}</p>
            </div>
          </div>

          {/* Conditions */}
          {deploymentData.conditions.length > 0 && (
            <div>
              <h3 className="font-semibold mb-2">Conditions</h3>
              <div className="space-y-2">
                {deploymentData.conditions.map((condition, index) => (
                  <div key={index} className="p-3 border rounded-lg">
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-medium">{condition.type}</span>
                      <Badge variant={condition.status === "True" ? "default" : "secondary"}>
                        {condition.status}
                      </Badge>
                    </div>
                    {condition.message && (
                      <p className="text-sm text-muted-foreground">{condition.message}</p>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Pod 목록 */}
          {deploymentData.pods && deploymentData.pods.length > 0 && (
            <div>
              <h3 className="font-semibold mb-2">Connected Pods ({deploymentData.pods.length}개)</h3>
              <div className="space-y-2">
                {deploymentData.pods.map((pod, index) => (
                  <div
                    key={index}
                    className="p-3 border rounded-lg hover:bg-muted/50 transition-colors"
                  >
                    <div className="flex items-center gap-2 mb-2">
                      {getStatusIcon(pod.phase)}
                      <code className="text-sm font-mono">{pod.name}</code>
                      <Badge variant={pod.phase === "Running" ? "default" : "secondary"}>
                        {pod.phase}
                      </Badge>
                    </div>
                    <div className="grid grid-cols-3 gap-2 text-sm text-muted-foreground">
                      <div>Ready: {pod.ready}</div>
                      <div>Restarts: {pod.restarts}</div>
                      <div>{pod.node}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 건강 상태 */}
          {metadata.is_healthy && metadata.ready === metadata.desired && (
            <div className="flex items-center gap-2 p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800">
              <CheckCircle className="w-4 h-4 text-green-600" />
              <span className="text-sm text-green-800 dark:text-green-200 font-medium">
                Deployment가 정상적으로 실행 중입니다 ({metadata.ready}/{metadata.desired})
              </span>
            </div>
          )}

          {!metadata.is_healthy && metadata.ready < metadata.desired && (
            <div className="flex items-center gap-2 p-3 bg-yellow-50 dark:bg-yellow-950/20 rounded-lg border border-yellow-200 dark:border-yellow-800">
              <AlertCircle className="w-4 h-4 text-yellow-600" />
              <span className="text-sm text-yellow-800 dark:text-yellow-200 font-medium">
                일부 Pod가 실행 중입니다 ({metadata.ready}/{metadata.desired})
              </span>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
