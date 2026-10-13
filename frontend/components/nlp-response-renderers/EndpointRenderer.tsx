"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ExternalLink, Copy, Globe, Server, Container } from "lucide-react"
import { NLPResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface EndpointData {
  service_name: string
  service_type: string
  cluster_ip: string
  ports: string
  namespace: string
  ingress_name: string
  ingress_domain?: string
  ingress_path?: string
  ingress_port?: number
  ingress_has_tls?: boolean
  service_endpoint?: string
  accessible_url?: string
  status?: string
  message?: string
}

interface EndpointRendererProps {
  response: NLPResponse
}

export function EndpointRenderer({ response }: EndpointRendererProps) {
  const { data, metadata } = response
  const endpointData = data.formatted as EndpointData
  const namespace = (metadata as any)?.namespace || endpointData?.namespace || "default"

  // 에러 상태인 경우
  if (endpointData?.status === "error") {
    return (
      <Card className="w-full">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-red-600">
            <Globe className="w-5 h-5" />
            엔드포인트 조회 오류
          </CardTitle>
          <CardDescription>{endpointData?.message || "엔드포인트를 찾을 수 없습니다."}</CardDescription>
        </CardHeader>
      </Card>
    )
  }

  const openInNewTab = (url: string) => {
    window.open(url, '_blank', 'noopener,noreferrer')
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Globe className="w-5 h-5 text-blue-500" />
          서비스 접속 정보
        </CardTitle>
        <CardDescription>
          {endpointData?.service_name}의 접속 정보를 찾았습니다
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* 서비스 정보 */}
        <div className="p-3 bg-muted/50 rounded-lg">
          <div className="flex items-center mb-3">
            <span className="text-sm font-medium">서비스 정보</span>
          </div>
          <div className="space-y-2 text-sm">
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">서비스 이름:</span>
              <span className="font-mono">{endpointData?.service_name}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">포트:</span>
              <span className="font-mono text-blue-600">{endpointData?.ports}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">네임스페이스:</span>
              <span className="font-mono">{endpointData?.namespace}</span>
            </div>
          </div>
        </div>

        {/* 인그리스 정보 */}
        <div className="p-3 bg-muted/50 rounded-lg">
          <div className="flex items-center mb-3">
            <span className="text-sm font-medium">인그리스 정보</span>
          </div>
          <div className="space-y-2 text-sm">
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">도메인 (Host):</span>
              <span className="font-mono text-blue-600">{endpointData?.ingress_domain || "-"}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">상태 (Status):</span>
              {endpointData?.ingress_has_tls ? (
                <Badge className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">
                  🟢 활성 (HTTPS)
                </Badge>
              ) : (
                <Badge variant="secondary">
                  🟡 활성 (HTTP)
                </Badge>
              )}
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">대상 서비스:</span>
              <span className="font-mono text-purple-600">{endpointData?.service_name}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">대상 포트:</span>
              <span className="font-mono text-blue-600">{endpointData?.ingress_port || "-"}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">경로 (Path):</span>
              <span className="font-mono text-blue-600">{endpointData?.ingress_path || "/"}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-medium w-28">보안 리디렉션:</span>
              {endpointData?.ingress_has_tls ? (
                <Badge variant="default" className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">
                  사용 중
                </Badge>
              ) : (
                <Badge variant="secondary">
                  미사용
                </Badge>
              )}
            </div>
          </div>
        </div>

        {/* 서비스 엔드포인트 */}
        {endpointData?.service_endpoint && (
          <div className="p-3 border rounded-lg">
            <div className="flex items-center gap-2 mb-2">
              <Server className="w-4 h-4 text-purple-500" />
              <span className="text-sm font-medium">서비스 엔드포인트</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm font-mono text-blue-600">{endpointData.service_endpoint}</span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => copyToClipboard(endpointData.service_endpoint!)}
              >
                <Copy className="w-3 h-3" />
              </Button>
            </div>
          </div>
        )}

        {/* 접속 가능한 URL */}
        {endpointData?.accessible_url && (
          <div className="p-3 border rounded-lg bg-green-50 dark:bg-green-950/20">
            <div className="flex items-center gap-2 mb-2">
              <Globe className="w-4 h-4 text-green-600" />
              <span className="text-sm font-medium">접속 가능한 URL</span>
            </div>
            <div className="flex items-center justify-between">
              <a
                href={endpointData.accessible_url}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm font-mono text-green-600 hover:text-green-800 hover:underline flex items-center gap-1"
              >
                <ExternalLink className="w-3 h-3" />
                {endpointData.accessible_url}
              </a>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => copyToClipboard(endpointData.accessible_url!)}
              >
                <Copy className="w-3 h-3" />
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}


