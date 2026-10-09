"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Network, Copy, Tag, ListTree, Server } from "lucide-react"
import { NLPResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { formatKst } from "@/lib/time"

interface ServiceDetailRendererProps {
  response: NLPResponse
}

export function ServiceDetailRenderer({ response }: ServiceDetailRendererProps) {
  const detail = (response as any).data?.formatted || {}
  const service = detail?.service || detail

  const name: string = service?.name || ""
  const namespace: string = service?.namespace || "default"
  const creationTimestamp: string = service?.creation_timestamp || ""
  const type: string = service?.type || "ClusterIP"
  const clusterIP: string = service?.cluster_ip || "-"
  const externalIPs: Array<string> = service?.external_ips || []
  const ports: Array<any> = service?.ports || []
  const selector: Record<string, string> = service?.selector || {}
  const labels: Record<string, string> = service?.labels || {}
  const annotations: Record<string, string> = service?.annotations || {}
  const endpoints = (service?.endpoints || {}) as { total?: number; addresses?: Array<any> }

  const tryFormatJson = (value: string) => {
    try {
      const parsed = JSON.parse(value)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return value
    }
  }

  const renderKeyValue = (obj: Record<string, string>) => {
    const entries = Object.entries(obj || {})
    if (entries.length === 0) return <p className="text-sm text-muted-foreground">없음</p>
    return (
      <div className="flex flex-wrap gap-2">
        {entries.map(([k, v]) => (
          <Badge key={k} variant="secondary" className="font-mono">{k}={String(v)}</Badge>
        ))}
      </div>
    )
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Network className="w-5 h-5" />
              Service 상세 정보
            </CardTitle>
            <CardDescription>{(response as any).summary}</CardDescription>
          </div>
          <Button variant="outline" size="sm" onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}>
            <Copy className="w-4 h-4 mr-2" />
            복사
          </Button>
        </div>
      </CardHeader>

      <CardContent className="space-y-6">
        {/* 기본 정보 */}
        <div className="space-y-3">
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">Name:</div>
            <div className="font-mono">{name}</div>
          </div>
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">Namespace:</div>
            <div className="font-mono">{namespace}</div>
          </div>
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">Type:</div>
            <div className="font-mono">{type}</div>
          </div>
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">ClusterIP:</div>
            <div className="font-mono">{clusterIP}</div>
          </div>
          {externalIPs && externalIPs.length > 0 && (
            <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
              <div className="font-medium text-muted-foreground whitespace-nowrap">External IPs:</div>
              <div className="font-mono">{externalIPs.join(", ")}</div>
            </div>
          )}
          {creationTimestamp && (
            <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
              <div className="font-medium text-muted-foreground whitespace-nowrap">CreationTimestamp:</div>
              <div className="font-mono">{formatKst(creationTimestamp)}</div>
            </div>
          )}
        </div>

        {/* Labels */}
        {Object.keys(labels || {}).length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">Labels:</div>
            {renderKeyValue(labels)}
          </div>
        )}

        {/* Annotations */}
        <div>
          <div className="font-medium text-sm mb-2">Annotations:</div>
          {Object.keys(annotations || {}).length === 0 ? (
            <p className="text-sm text-muted-foreground">없음</p>
          ) : (
            <div className="space-y-2 pl-4">
              {Object.entries(annotations).map(([key, value]) => {
                const maybeJson = typeof value === "string" && (value.trim().startsWith("{") || value.trim().startsWith("["))
                const isLong = typeof value === "string" && value.length > 160
                return (
                  <div key={key} className="text-sm">
                    <code className="font-mono">{key}:</code>{" "}
                    {maybeJson || isLong ? (
                      <details>
                        <summary className="text-xs text-muted-foreground cursor-pointer inline">자세히 보기</summary>
                        <pre className="mt-1 text-xs bg-muted/40 p-2 rounded overflow-x-auto whitespace-pre-wrap break-words">{maybeJson ? tryFormatJson(value as string) : String(value)}</pre>
                      </details>
                    ) : (
                      <span className="font-mono">{String(value)}</span>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* Selector */}
        {Object.keys(selector || {}).length > 0 && (
          <div>
            <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
              <div className="font-medium text-muted-foreground">Selector:</div>
              {renderKeyValue(selector)}
            </div>
          </div>
        )}

        {/* Ports */}
        {ports && ports.length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">Ports:</div>
            <div className="overflow-x-auto border rounded-lg">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/50">
                    <th className="text-left py-2 px-3 font-medium">Name</th>
                    <th className="text-left py-2 px-3 font-medium">Port</th>
                    <th className="text-left py-2 px-3 font-medium">TargetPort</th>
                    <th className="text-left py-2 px-3 font-medium">Protocol</th>
                  </tr>
                </thead>
                <tbody>
                  {ports.map((p: any, idx: number) => (
                    <tr key={idx} className="border-b">
                      <td className="py-2 px-3 font-mono">{p?.name || '-'}</td>
                      <td className="py-2 px-3 font-mono">{p?.port ?? '-'}</td>
                      <td className="py-2 px-3 font-mono">{p?.target_port ?? '-'}</td>
                      <td className="py-2 px-3 font-mono">{p?.protocol || 'TCP'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Endpoints */}
        {endpoints && Array.isArray(endpoints.addresses) && endpoints.addresses.length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">Endpoints ({endpoints.total ?? endpoints.addresses.length}):</div>
            <div className="space-y-2">
              {endpoints.addresses.map((addr: any, idx: number) => (
                <div key={idx} className="p-3 border rounded-lg">
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-2 text-sm">
                    <div>
                      <div className="text-muted-foreground">IP</div>
                      <div className="font-mono">{addr?.ip || '-'}</div>
                    </div>
                    <div>
                      <div className="text-muted-foreground">TargetRef</div>
                      <div className="font-mono break-all">{addr?.target_ref?.name || '-'}</div>
                    </div>
                    <div>
                      <div className="text-muted-foreground">Kind</div>
                      <div className="font-mono">{addr?.target_ref?.kind || '-'}</div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Raw */}
        {(response as any).data?.raw && (
          <details>
            <summary className="text-sm text-muted-foreground cursor-pointer">원본 데이터</summary>
            <pre className="mt-2 text-xs bg-muted/40 p-2 rounded overflow-x-auto">{JSON.stringify((response as any).data.raw, null, 2)}</pre>
          </details>
        )}
      </CardContent>
    </Card>
  )
}






