"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, Network, CheckCircle, AlertCircle, Globe, HelpCircle, List } from "lucide-react"
import { ServiceStatusResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface ServiceStatusRendererProps {
  response: ServiceStatusResponse
}

export function ServiceStatusRenderer({ response }: ServiceStatusRendererProps) {
  const { data, metadata } = response
  const serviceData = data.formatted
  
  // 에러 상태 처리 - 개선된 UI
  if ((serviceData as any).error) {
    const errorMessage = (serviceData as any).message || ""
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
                Service를 찾을 수 없습니다
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

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Network className="w-5 h-5" />
              Service 상태
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
          {/* 서비스 정보 */}
          <div className="p-4 bg-muted/50 rounded-lg">
            <div className="flex items-center gap-2 mb-3">
              <code className="text-lg font-mono bg-background px-2 py-1 rounded">
                {serviceData.name}
              </code>
              <Badge variant="outline">{serviceData.type}</Badge>
            </div>
            <p className="text-sm text-muted-foreground">
              네임스페이스: {serviceData.namespace}
            </p>
          </div>

          {/* 주요 정보 */}
          <div className="grid grid-cols-2 gap-4">
            <div className="p-4 border rounded-lg">
              <div className="flex items-center gap-2 mb-2">
                <Globe className="w-4 h-4 text-muted-foreground" />
                <span className="text-sm font-medium">Cluster IP</span>
              </div>
              <code className="text-lg font-mono">{serviceData.cluster_ip}</code>
            </div>
            
            <div className="p-4 border rounded-lg">
              <div className="flex items-center gap-2 mb-2">
                <CheckCircle className="w-4 h-4 text-muted-foreground" />
                <span className="text-sm font-medium">Ready Endpoints</span>
              </div>
              <p className="text-2xl font-bold">{serviceData.ready_endpoints}</p>
            </div>
          </div>

          {/* 포트 정보 */}
          {serviceData.ports.length > 0 && (
            <div>
              <h3 className="font-semibold mb-2">Ports</h3>
              <div className="space-y-2">
                {serviceData.ports.map((port, index) => (
                  <div key={index} className="p-3 border rounded-lg">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary">{port.protocol}</Badge>
                        <span className="font-mono">:{port.port}</span>
                        <span className="text-sm text-muted-foreground">
                          → {port.target_port}
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Selector 정보 */}
          {Object.keys(serviceData.selector).length > 0 && (
            <div>
              <h3 className="font-semibold mb-2">Selector</h3>
              <div className="flex flex-wrap gap-2">
                {Object.entries(serviceData.selector).map(([key, value]) => (
                  <Badge key={key} variant="outline">
                    {key}={value}
                  </Badge>
                ))}
              </div>
            </div>
          )}

          {/* 건강 상태 */}
          {metadata.is_healthy && serviceData.ready_endpoints > 0 && (
            <div className="flex items-center gap-2 p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800">
              <CheckCircle className="w-4 h-4 text-green-600" />
              <span className="text-sm text-green-800 dark:text-green-200 font-medium">
                Service가 {serviceData.ready_endpoints}개의 Pod에 연결되어 있습니다
              </span>
            </div>
          )}

          {!metadata.is_healthy && serviceData.ready_endpoints === 0 && (
            <div className="flex items-center gap-2 p-3 bg-yellow-50 dark:bg-yellow-950/20 rounded-lg border border-yellow-200 dark:border-yellow-800">
              <AlertCircle className="w-4 h-4 text-yellow-600" />
              <span className="text-sm text-yellow-800 dark:text-yellow-200 font-medium">
                연결된 Pod가 없습니다
              </span>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
