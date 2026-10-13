"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { 
  Copy, 
  Server, 
  Activity, 
  CheckCircle, 
  AlertTriangle,
  XCircle,
  Clock,
  AlertCircle
} from "lucide-react"
import { OverviewResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { cn } from "@/lib/utils"

interface OverviewRendererProps {
  response: OverviewResponse
}

interface ReportSection {
  title: string
  type: string
  items?: any[]
  data?: any
}

export function OverviewRenderer({ response }: OverviewRendererProps) {
  const { data } = response
  const formatted = data.formatted || {}
  const report_sections = formatted.report_sections || []
  const summary = formatted.summary as {
    total_deployments?: number
    total_pods?: number
    total_services?: number
    total_namespaces?: number
    [key: string]: any
  } || {}
  const metadata = response.metadata || {}

  const renderSection = (section: ReportSection) => {
    switch (section.type) {
      case "cluster_info":
        return <ClusterInfoSection section={section} />
      
      case "critical":
        return <CriticalIssuesSection section={section} />
      
      case "warning":
        return <WarningSection section={section} />
      
      case "workloads":
        return <WorkloadsSection section={section} />
      
      case "external_services":
        return <ExternalServicesSection section={section} />
      
      default:
        return null
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="flex items-center gap-3 text-2xl font-bold">
            <Server className="w-6 h-6" />
            클러스터 전체 현황 보고서
          </CardTitle>
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
      
      <CardContent className="space-y-6">
        {report_sections.map((section: ReportSection, index: number) => (
          <div key={index}>
            {renderSection(section)}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

function ClusterInfoSection({ section }: { section: ReportSection }) {
  const cluster_name = section.data?.cluster_name || "Unknown Cluster"
  const total_nodes = section.data?.total_nodes || 0
  const nodes = section.data?.nodes || []

  return (
    <Card className="bg-blue-50 dark:bg-blue-950/10">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Server className="w-5 h-5 text-blue-600" />
          {section.title}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* 상단: 클러스터 이름과 노드 개수 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div>
            <div className="text-sm text-muted-foreground mb-2">클러스터 이름</div>
            <div className="font-medium text-lg">{cluster_name}</div>
          </div>
          <div>
            <div className="text-sm text-muted-foreground mb-2">노드 개수</div>
            <div className="font-medium text-lg">{total_nodes}개</div>
          </div>
        </div>
        
        {/* 하단: 노드 목록 */}
        <div>
          <div className="text-sm text-muted-foreground mb-3">노드 목록</div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[60%]">노드 이름</TableHead>
                <TableHead className="text-right">상태</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {nodes.map((node: any, index: number) => (
                <TableRow key={index}>
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <Server className="w-4 h-4" />
                      {node.name}
                    </div>
                  </TableCell>
                  <TableCell className="text-right">
                    <Badge
                      variant={node.status === "Ready" ? "default" : "destructive"}
                      className={cn(
                        node.status === "Ready" 
                          ? "bg-green-100 text-green-800"
                          : "bg-red-100 text-red-800"
                      )}
                    >
                      {node.status}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function CriticalIssuesSection({ section }: { section: ReportSection }) {
  const items = section.items || []

  if (items.length === 0) return null

  return (
    <Card className="bg-red-50 dark:bg-red-950/10 border-red-200 dark:border-red-800">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg text-red-700">
          <AlertTriangle className="w-5 h-5" />
          {section.title}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          {/* Deployment Warnings */}
          {items.filter((item: any) => item.type === "deployment_warning").map((item: any, index: number) => (
            <div key={`deployment-${index}`} className="bg-white dark:bg-gray-800 p-3 rounded border-l-4 border-red-500">
              <div className="flex items-center gap-2 mb-1">
                <XCircle className="w-4 h-4 text-red-500" />
                <span className="font-semibold">Deployment 경고</span>
              </div>
              <div className="text-sm space-y-1">
                <div><strong>Namespace:</strong> {item.namespace}</div>
                <div><strong>Name:</strong> {item.name}</div>
                <div><strong>Ready:</strong> {item.ready} (100% 미만)</div>
              </div>
            </div>
          ))}

          {/* Pending Pods */}
          {items.filter((item: any) => item.type === "pending_pod").map((item: any, index: number) => (
            <div key={`pending-${index}`} className="bg-white dark:bg-gray-800 p-3 rounded border-l-4 border-yellow-500">
              <div className="flex items-center gap-2 mb-1">
                <Clock className="w-4 h-4 text-yellow-500" />
                <span className="font-semibold">Pending Pod</span>
              </div>
              <div className="text-sm space-y-1">
                <div><strong>Namespace:</strong> {item.namespace}</div>
                <div><strong>Name:</strong> {item.name}</div>
              </div>
            </div>
          ))}

          {/* Failed Pods */}
          {items.filter((item: any) => item.type === "failed_pod").map((item: any, index: number) => (
            <div key={`failed-${index}`} className="bg-white dark:bg-gray-800 p-3 rounded border-l-4 border-red-500">
              <div className="flex items-center gap-2 mb-1">
                <XCircle className="w-4 h-4 text-red-500" />
                <span className="font-semibold">Failed Pod</span>
              </div>
              <div className="text-sm space-y-1">
                <div><strong>Namespace:</strong> {item.namespace}</div>
                <div><strong>Name:</strong> {item.name}</div>
                <div><strong>Status:</strong> {item.status}</div>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function WarningSection({ section }: { section: ReportSection }) {
  const items = section.items || []

  if (items.length === 0) return null

  return (
    <Card className="bg-yellow-50 dark:bg-yellow-950/10 border-yellow-200 dark:border-yellow-800">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg text-yellow-700">
          <AlertCircle className="w-5 h-5" />
          {section.title}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          {items.map((item: any, index: number) => (
            <div key={index} className="bg-white dark:bg-gray-800 p-3 rounded border-l-4 border-yellow-500">
              <div className="mb-1">
                <span className="font-semibold">Pod 재시작 횟수 높음</span>
              </div>
              <div className="text-sm space-y-1">
                <div><strong>Namespace:</strong> {item.namespace}</div>
                <div><strong>Name:</strong> {item.name}</div>
                <div><strong>재시작:</strong> {item.restarts}회</div>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function WorkloadsSection({ section }: { section: ReportSection }) {
  const workloads = section.data || []

  if (workloads.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Activity className="w-5 h-5" />
          {section.title}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>네임스페이스</TableHead>
              <TableHead>Deployments</TableHead>
              <TableHead>Pods</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {workloads.map((workload: any, index: number) => (
              <TableRow key={index}>
                <TableCell>
                  <Badge variant="outline">{workload.namespace}</Badge>
                </TableCell>
                <TableCell>
                  <Badge variant="default" className="bg-blue-100 text-blue-800">
                    {workload.deployments}개
                  </Badge>
                </TableCell>
                <TableCell>
                  <Badge variant="default" className="bg-green-100 text-green-800">
                    {workload.pods}개
                  </Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function ExternalServicesSection({ section }: { section: ReportSection }) {
  const external_data = section.data || {}
  const load_balancers = external_data.load_balancer || []
  const node_ports = external_data.node_port || []

  if (load_balancers.length === 0 && node_ports.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Server className="w-5 h-5" />
          {section.title}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {load_balancers.length > 0 && (
          <div>
            <div className="font-semibold mb-2">LoadBalancer ({load_balancers.length}개):</div>
            {load_balancers.map((svc: any, index: number) => (
              <div key={index} className="ml-4 text-sm">
                <code className="text-blue-600">{svc.namespace}/{svc.name}</code>
              </div>
            ))}
          </div>
        )}

        {node_ports.length > 0 && (
          <div>
            <div className="font-semibold mb-2">NodePort ({node_ports.length}개):</div>
            {node_ports.map((svc: any, index: number) => (
              <div key={index} className="ml-4 text-sm">
                <code className="text-green-600">{svc.namespace}/{svc.name}</code>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}