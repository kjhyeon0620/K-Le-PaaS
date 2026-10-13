"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Copy, Folder, CheckCircle, XCircle } from "lucide-react"
import { NamespaceListResponse, NamespaceInfo } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface NamespaceListRendererProps {
  response: NamespaceListResponse
}

export function NamespaceListRenderer({ response }: NamespaceListRendererProps) {
  const { data, metadata } = response
  const namespaces = data.formatted as NamespaceInfo[]
  const total_namespaces = namespaces.length


  const getStatusBadge = (status: string) => {
    switch (status.toLowerCase()) {
      case 'active':
        return (
          <div className="flex items-center gap-1">
            <CheckCircle className="w-4 h-4 text-green-500" />
            <Badge variant="default" className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">
              Active
            </Badge>
          </div>
        )
      case 'terminating':
        return (
          <div className="flex items-center gap-1">
            <XCircle className="w-4 h-4 text-yellow-500" />
            <Badge variant="secondary" className="bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200">
              Terminating
            </Badge>
          </div>
        )
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Folder className="w-5 h-5" />
              Namespace 목록
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
          <Badge variant="default">총 {total_namespaces}개</Badge>
        </div>
      </CardHeader>
      
      <CardContent>
        {total_namespaces === 0 ? (
          <p className="text-muted-foreground">현재 Namespace가 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>경과 시간</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {namespaces.map((namespace, index) => (
                <TableRow key={`${namespace.name}-${index}`}>
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      {namespace.name}
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => copyToClipboard(namespace.name)}
                      >
                        <Copy className="w-3 h-3" />
                      </Button>
                    </div>
                  </TableCell>
                  <TableCell>
                    {getStatusBadge(namespace.status)}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="text-xs">
                      {namespace.age}
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
