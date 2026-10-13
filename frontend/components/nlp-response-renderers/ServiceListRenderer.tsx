"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Server, Copy } from "lucide-react"
import { ServiceListResponse, ServiceInfo } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface ServiceListRendererProps {
  response: ServiceListResponse
}

export function ServiceListRenderer({ response }: ServiceListRendererProps) {
  const { data, metadata } = response
  const services = data.formatted as ServiceInfo[]
  const namespace = metadata?.namespace || "default"
  const total_services = services.length

  const openInNewTab = (url: string) => {
    console.log('Opening URL:', url) // 디버깅용 로그
    window.open(url, '_blank', 'noopener,noreferrer')
  }


  const getServiceEndpoint = (service: ServiceInfo) => {
    // 1. External IP가 있으면 우선 사용 (LoadBalancer)
    if (service.external_ip && 
        service.external_ip !== '<none>' && 
        service.external_ip !== '') {
      return `http://${service.external_ip}`
    }
    
    // 2. -svc로 끝나는 서비스는 Ingress 도메인 사용
    if (service.name.endsWith('-svc')) {
      const repoName = service.name.replace('-svc', '')
      const ingressDomain = `${repoName}.klepaas.app`
      return `https://${ingressDomain}`
    }
    
    // 3. LoadBalancer 타입이지만 External IP가 없는 경우
    if (service.type.toLowerCase() === 'loadbalancer') {
      const repoName = service.name.replace('-svc', '')
      const ingressDomain = `${repoName}.klepaas.app`
      return `https://${ingressDomain}`
    }
    
    // 기본값 (이 경우는 hasIngressDomain에서 false를 반환해야 함)
    return ''
  }

  const hasIngressDomain = (service: ServiceInfo) => {
    // 더 정확한 인그리스 도메인 확인 방법들:
    
    // 1. 서비스 이름이 -svc로 끝나는지 확인 (기본 패턴)
    const hasSvcSuffix = service.name.endsWith('-svc')
    
    // 2. External IP가 있는지 확인 (LoadBalancer 타입)
    const hasExternalIP = service.external_ip && 
                         service.external_ip !== '<none>' && 
                         service.external_ip !== ''
    
    // 3. 서비스 타입이 LoadBalancer인지 확인
    const isLoadBalancer = service.type.toLowerCase() === 'loadbalancer'
    
    // 인그리스 도메인이 있다고 판단하는 조건:
    // -svc로 끝나거나, External IP가 있거나, LoadBalancer 타입
    return hasSvcSuffix || hasExternalIP || isLoadBalancer
  }

  const getServiceTypeBadge = (service: ServiceInfo) => {
    const isService = service.name.endsWith('-svc')
    const isSystem = service.namespace === 'kube-system' || 
                     service.name.startsWith('kubernetes') || 
                     service.namespace === 'monitor' ||
                     service.namespace === 'monitoring'
    const isIngress = service.namespace === 'ingress-nginx'
    
    return (
      <div className="flex items-center gap-1">
        {isService && (
          <Badge variant="secondary" className="text-xs">
            Service
          </Badge>
        )}
        {isSystem && (
          <Badge variant="outline" className="text-xs bg-white text-black border-black">
            System
          </Badge>
        )}
        {isIngress && (
          <Badge variant="outline" className="text-xs bg-white text-black border-black">
            Ingress
          </Badge>
        )}
      </div>
    )
  }


  return (
    <Card className="w-full">
      <CardHeader>
        <div>
          <CardTitle className="flex items-center gap-2">
            <Server className="w-5 h-5" />
            Service 목록
          </CardTitle>
          <CardDescription>
            {response.message}
          </CardDescription>
        </div>
        
        <div className="flex items-center gap-2 mt-4">
          <Badge variant="outline">네임스페이스: {namespace}</Badge>
          <Badge variant="default">총 {total_services}개</Badge>
        </div>
      </CardHeader>
      
      <CardContent>
        {total_services === 0 ? (
          <p className="text-muted-foreground">현재 실행 중인 Service가 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[250px]">이름</TableHead>
                <TableHead className="w-[150px]">타입</TableHead>
                <TableHead className="w-[150px]">Cluster IP</TableHead>
                <TableHead className="w-[250px]">도메인</TableHead>
                <TableHead className="w-[150px]">네임스페이스</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {services.map((service, index) => (
                <TableRow key={`${service.name}-${service.namespace}-${index}`}>
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <span className="text-foreground">
                        {service.name}
                      </span>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-6 w-6 p-0"
                        onClick={() => copyToClipboard(service.name)}
                      >
                        <Copy className="h-3 w-3" />
                      </Button>
                    </div>
                  </TableCell>
                  <TableCell>
                    {getServiceTypeBadge(service)}
                  </TableCell>
                  <TableCell>
                    <code className="text-sm font-mono bg-muted px-2 py-1 rounded">
                      {service.cluster_ip}
                    </code>
                  </TableCell>
                  <TableCell>
                    {hasIngressDomain(service) ? (
                      <span className="text-blue-600 hover:text-blue-800 cursor-pointer underline"
                            onClick={() => openInNewTab(getServiceEndpoint(service))}>
                        {getServiceEndpoint(service).replace('https://', '').replace('http://', '')}
                      </span>
                    ) : (
                      <span className="text-muted-foreground">-</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="text-xs">
                      {service.namespace}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}
