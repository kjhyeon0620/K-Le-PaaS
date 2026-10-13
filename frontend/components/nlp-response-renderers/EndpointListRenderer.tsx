"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Button } from "@/components/ui/button"
import { Copy, ExternalLink, Network } from "lucide-react"
import { EndpointListResponse, EndpointListInfo } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface EndpointListRendererProps {
  response: EndpointListResponse
}

export function EndpointListRenderer({ response }: EndpointListRendererProps) {
  const { data, metadata } = response
  const endpoints = data.formatted as EndpointListInfo[]
  
  // 실제 개수 계산 - URL은 Ingress 도메인 또는 LoadBalancer IP가 있는 서비스
  const actualServicesWithURL = endpoints.filter(e => 
    (e.ingress_domains && e.ingress_domains.length > 0) || e.external_access
  ).length
  
  const getServiceTypeBadge = (type: string) => {
    switch (type.toLowerCase()) {
      case "clusterip":
        return <Badge variant="secondary">ClusterIP</Badge>
      case "nodeport":
        return <Badge variant="outline">NodePort</Badge>
      case "loadbalancer":
        return <Badge variant="default">LoadBalancer</Badge>
      case "externalname":
        return <Badge variant="outline">ExternalName</Badge>
      default:
        return <Badge variant="outline">{type}</Badge>
    }
  }

  const formatPorts = (ports: Array<{ port: number | string, target_port: number | string, protocol: string }>) => {
    if (ports.length === 0) return "-"
    return ports.map(p => `${p.port}:${p.target_port}/${p.protocol}`).join(", ")
  }

  const getExternalAccess = (endpoint: EndpointListInfo) => {
    // Ingress 도메인이 있으면 우선 사용
    if (endpoint.ingress_domains && endpoint.ingress_domains.length > 0) {
      return endpoint.ingress_domains[0].domain
    }
    // LoadBalancer 외부 접근이 있으면 사용
    if (endpoint.external_access) {
      const port = endpoint.external_access.ports?.[0]?.port
      if (port) {
        return `${endpoint.external_access.address}:${port}`
      }
      return endpoint.external_access.address
    }
    return null
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Network className="w-5 h-5" />
              엔드포인트 목록
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
          <Badge variant="outline">
            총 서비스: {endpoints.length}개
          </Badge>
          <Badge variant="secondary">
            URL: {actualServicesWithURL}개
          </Badge>
          <div className="text-sm text-muted-foreground">
            네임스페이스: {metadata.namespace}
          </div>
        </div>
      </CardHeader>
      
      <CardContent>
        {endpoints.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            엔드포인트가 없습니다.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>서비스 이름</TableHead>
                  <TableHead>타입</TableHead>
                  <TableHead>Cluster IP</TableHead>
                  <TableHead>포트</TableHead>
                  <TableHead>서비스 엔드포인트</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {endpoints.map((endpoint, index) => {
                  const externalAccess = getExternalAccess(endpoint)
                  return (
                    <TableRow key={`${endpoint.service_name}-${index}`}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-sm text-foreground">
                            {endpoint.service_name}
                          </span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => copyToClipboard(endpoint.service_name)}
                            title="서비스 이름 복사"
                          >
                            <Copy className="w-3 h-3" />
                          </Button>
                        </div>
                      </TableCell>
                      <TableCell>
                        {getServiceTypeBadge(endpoint.service_type)}
                      </TableCell>
                      <TableCell>
                        <code className="text-xs bg-muted px-2 py-1 rounded">
                          {endpoint.cluster_ip}
                        </code>
                      </TableCell>
                      <TableCell>
                        <span className="text-sm text-muted-foreground">
                          {formatPorts(endpoint.ports)}
                        </span>
                      </TableCell>
                      <TableCell>
                        {endpoint.service_endpoint ? (
                          <div className="flex items-center gap-2">
                            <code className="text-xs bg-muted px-2 py-1 rounded font-mono">
                              {endpoint.service_endpoint}
                            </code>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => copyToClipboard(endpoint.service_endpoint!)}
                              title="서비스 엔드포인트 복사"
                            >
                              <Copy className="w-3 h-3" />
                            </Button>
                          </div>
                        ) : (
                          <span className="text-muted-foreground">-</span>
                        )}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

