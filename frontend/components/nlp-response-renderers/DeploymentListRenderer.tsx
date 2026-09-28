"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Copy, Package, CheckCircle, XCircle } from "lucide-react"
import { DeploymentListResponse, DeploymentInfo } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface DeploymentListRendererProps {
  response: DeploymentListResponse
}

export function DeploymentListRenderer({ response }: DeploymentListRendererProps) {
  const { data, metadata } = response
  const deployments = data.formatted as DeploymentInfo[]
  const namespace = metadata?.namespace || "default"
  const total_deployments = deployments.length


  const getDeploymentStatus = (status: string, failReason: string | null) => {
    if (status === "SUCCESS") {
      return (
        <div className="flex items-center gap-1">
          <CheckCircle className="w-4 h-4 text-green-500" />
          <Badge variant="default" className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">
            {status}
          </Badge>
        </div>
      )
    }

    if (status === "FAILED") {
      return (
        <div className="flex items-center gap-1" title={failReason ?? undefined}>
          <XCircle className="w-4 h-4 text-red-500" />
          <Badge variant="destructive">
            {status}
          </Badge>
        </div>
      )
    }

    return <Badge variant="outline">{status}</Badge>
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Package className="w-5 h-5" />
              Deployment 목록
            </CardTitle>
            <CardDescription>
              {response.message}
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
        
        <div className="flex items-center gap-2 mt-4">
          <Badge variant="outline">네임스페이스: {namespace}</Badge>
          <Badge variant="default">총 {total_deployments}개</Badge>
        </div>
      </CardHeader>
      
      <CardContent>
        {total_deployments === 0 ? (
          <p className="text-muted-foreground">배포 이력이 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>브랜치</TableHead>
                <TableHead>커밋</TableHead>
                <TableHead>생성 시각</TableHead>
                <TableHead>이미지</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {deployments.map((deployment, index) => (
                <TableRow key={`${deployment.id}-${index}`}>
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      {deployment.name}
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => copyToClipboard(deployment.name)}
                      >
                        <Copy className="w-3 h-3" />
                      </Button>
                    </div>
                  </TableCell>
                  <TableCell>
                    {getDeploymentStatus(deployment.status, deployment.fail_reason)}
                  </TableCell>
                  <TableCell className="text-xs">{deployment.branch ?? "-"}</TableCell>
                  <TableCell className="font-mono text-xs">{deployment.commit?.slice(0, 7) ?? "-"}</TableCell>
                  <TableCell className="text-xs">{deployment.created_at ?? "-"}</TableCell>
                  <TableCell className="max-w-[240px] truncate font-mono text-xs" title={deployment.image ?? undefined}>
                    {deployment.image ?? "-"}
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
