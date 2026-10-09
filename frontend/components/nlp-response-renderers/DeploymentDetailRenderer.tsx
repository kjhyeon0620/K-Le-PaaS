"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Layers, Copy, CalendarClock, Tag, FileText, Boxes, Settings, ListTree, Package, HardDrive, Server, Activity } from "lucide-react"
import { DeploymentDetailResponse, NLPResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { formatKst } from "@/lib/time"

interface DeploymentDetailRendererProps {
  response: DeploymentDetailResponse | NLPResponse
}

export function DeploymentDetailRenderer({ response }: DeploymentDetailRendererProps) {
  const detail = (response as DeploymentDetailResponse).data?.formatted || {}
  const deployment = detail?.deployment || detail

  const name: string = deployment?.name || ""
  const namespace: string = deployment?.namespace || "default"
  const creationTimestamp: string = deployment?.creation_timestamp || ""
  const replicas = deployment?.replicas || {}
  const labels: Record<string, string> = deployment?.labels || {}
  const annotations: Record<string, string> = deployment?.annotations || {}
  const selector: Record<string, string> = deployment?.selector || {}
  const strategy = deployment?.strategy || {}
  const minReadySeconds = deployment?.min_ready_seconds || 0
  const conditions: Array<any> = deployment?.conditions || []
  const podTemplate = deployment?.pod_template || {}
  const replicaSets = deployment?.replica_sets || {}
  const events: Array<any> = deployment?.events || []

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
      <div className="space-y-2">
        {entries.map(([key, value]) => {
          const isLong = typeof value === "string" && value.length > 220
          const maybeJson = typeof value === "string" && (value.trim().startsWith("{") || value.trim().startsWith("["))
          const content = maybeJson ? tryFormatJson(value as string) : value
          return (
            <div key={key} className="p-3 border rounded-lg">
              <div className="flex items-center gap-2 mb-2">
                <Tag className="w-4 h-4 text-muted-foreground" />
                <code className="text-sm font-mono break-all">{key}</code>
              </div>
              {isLong || maybeJson ? (
                <details>
                  <summary className="text-xs text-muted-foreground cursor-pointer">자세히 보기</summary>
                  <pre className="mt-2 text-xs bg-muted/40 p-2 rounded overflow-x-auto whitespace-pre-wrap break-words">{content}</pre>
                </details>
              ) : (
                <div className="text-sm font-mono break-words">{String(content)}</div>
              )}
            </div>
          )
        })}
      </div>
    )
  }

  // Special report for kubectl last-applied-configuration
  const renderLastAppliedReport = (jsonString: string) => {
    try {
      const obj = JSON.parse(jsonString)
      const metadata = obj?.metadata || {}
      const spec = obj?.spec || {}
      const selectorObj = spec?.selector || {}
      const template = spec?.template || {}
      const containers: Array<any> = template?.spec?.containers || []
      const strategyObj = spec?.strategy || {}
      const replicasValue = spec?.replicas

      return (
        <div className="space-y-4">
          {/* Metadata Summary */}
          <div className="p-4 rounded-lg border bg-muted/40">
            <div className="flex items-center gap-2 mb-2">
              <Boxes className="w-4 h-4" />
              <span className="text-sm font-medium">메타데이터 요약</span>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-sm">
              <div>
                <div className="text-muted-foreground">이름</div>
                <div className="font-mono">{metadata?.name || '-'}</div>
              </div>
              <div>
                <div className="text-muted-foreground">네임스페이스</div>
                <div className="font-mono">{metadata?.namespace || 'default'}</div>
              </div>
              <div>
                <div className="text-muted-foreground">라벨 수</div>
                <div className="font-mono">{Object.keys(metadata?.labels || {}).length}</div>
              </div>
            </div>
          </div>

          {/* Spec Summary */}
          <div className="p-4 rounded-lg border bg-muted/40">
            <div className="flex items-center gap-2 mb-2">
              <Settings className="w-4 h-4" />
              <span className="text-sm font-medium">스펙 요약</span>
            </div>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-center">
              <div className="p-3 border rounded-lg">
                <div className="text-xs text-muted-foreground">Replicas</div>
                <div className="text-xl font-bold">{replicasValue ?? '-'}</div>
              </div>
              <div className="p-3 border rounded-lg">
                <div className="text-xs text-muted-foreground">전략</div>
                <div className="text-sm font-mono">{strategyObj?.type || '-'}</div>
              </div>
              <div className="p-3 border rounded-lg">
                <div className="text-xs text-muted-foreground">컨테이너</div>
                <div className="text-xl font-bold">{containers.length}</div>
              </div>
              <div className="p-3 border rounded-lg">
                <div className="text-xs text-muted-foreground">Selector(Label)</div>
                <div className="text-xl font-bold">{Object.keys(selectorObj?.matchLabels || {}).length}</div>
              </div>
            </div>
          </div>

          {/* Containers Table */}
          {containers.length > 0 && (
            <div className="p-4 rounded-lg border">
              <div className="flex items-center gap-2 mb-3">
                <Package className="w-4 h-4" />
                <span className="text-sm font-medium">컨테이너</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-2 pr-4 whitespace-nowrap">이름</th>
                      <th className="py-2 pr-4 whitespace-nowrap">이미지</th>
                      <th className="py-2 pr-4 whitespace-nowrap">포트</th>
                      <th className="py-2 pr-4 whitespace-nowrap">환경변수</th>
                    </tr>
                  </thead>
                  <tbody>
                    {containers.map((c, idx) => (
                      <tr key={idx} className="border-t">
                        <td className="py-2 pr-4 font-mono">{c?.name}</td>
                        <td className="py-2 pr-4 font-mono">{c?.image}</td>
                        <td className="py-2 pr-4 font-mono">{(c?.ports || []).map((p: any) => p?.containerPort).filter(Boolean).join(', ') || '-'}</td>
                        <td className="py-2 pr-4 font-mono">{(c?.env || []).length || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Selector Details */}
          {selectorObj?.matchLabels && (
            <div className="p-4 rounded-lg border bg-muted/40">
              <div className="flex items-center gap-2 mb-2">
                <ListTree className="w-4 h-4" />
                <span className="text-sm font-medium">Selector (matchLabels)</span>
              </div>
              <div className="flex flex-wrap gap-2">
                {Object.entries(selectorObj.matchLabels).map(([k, v]) => (
                  <Badge key={k} variant="secondary" className="font-mono">{k}={String(v)}</Badge>
                ))}
              </div>
            </div>
          )}

          {/* Raw last-applied */}
          <details>
            <summary className="text-xs text-muted-foreground cursor-pointer">원본 last-applied-configuration</summary>
            <pre className="mt-2 text-xs bg-muted/40 p-2 rounded overflow-x-auto">{JSON.stringify(obj, null, 2)}</pre>
          </details>
        </div>
      )
    } catch {
      return (
        <pre className="text-xs bg-muted/40 p-2 rounded overflow-x-auto whitespace-pre-wrap break-words">{jsonString}</pre>
      )
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Layers className="w-5 h-5" />
              Deployment 상세 정보
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
        {/* 기본 정보 - kubectl describe 형식 */}
        <div className="space-y-3">
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">Name:</div>
            <div className="font-mono">{name}</div>
          </div>
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">Namespace:</div>
            <div className="font-mono">{namespace}</div>
          </div>
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
            <div className="flex flex-wrap gap-2">
              {Object.entries(labels).map(([k, v]) => (
                <Badge key={k} variant="secondary" className="font-mono">
                  {k}={String(v)}
                </Badge>
              ))}
            </div>
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
                if (key === "kubectl.kubernetes.io/last-applied-configuration") {
                  return (
                    <div key={key} className="space-y-2">
                      <div className="flex items-center gap-2">
                        <code className="text-sm font-mono">{key}:</code>
                      </div>
                      <div className="pl-4">{renderLastAppliedReport(value)}</div>
                    </div>
                  )
                }
                const isLong = typeof value === "string" && value.length > 100
                return (
                  <div key={key} className="text-sm">
                    <code className="font-mono">{key}:</code>{" "}
                    {isLong ? (
                      <details>
                        <summary className="text-xs text-muted-foreground cursor-pointer inline">자세히 보기</summary>
                        <pre className="mt-1 text-xs bg-muted/40 p-2 rounded overflow-x-auto whitespace-pre-wrap break-words">{String(value)}</pre>
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
              <div className="flex flex-wrap gap-2">
              {Object.entries(selector).map(([k, v]) => (
                  <Badge key={k} variant="secondary" className="font-mono">
                  {k}={String(v)}
                  </Badge>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Replicas - kubectl describe 형식 */}
        {replicas && (
          <div>
            <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
              <div className="font-medium text-muted-foreground">Replicas:</div>
              <div className="font-mono">
                {replicas.desired ?? 0} desired | {replicas.updated ?? 0} updated | {replicas.current ?? 0} total | {replicas.available ?? 0} available | {replicas.unavailable ?? 0} unavailable
              </div>
            </div>
          </div>
        )}

        {/* Strategy */}
        <div>
          <div className="grid grid-cols-[160px_1fr] gap-2 text-sm">
            <div className="font-medium text-muted-foreground">StrategyType:</div>
            <div className="font-mono">{strategy?.type || '-'}</div>
          </div>
          {minReadySeconds !== undefined && (
            <div className="grid grid-cols-[160px_1fr] gap-2 text-sm mt-1">
              <div className="font-medium text-muted-foreground">MinReadySeconds:</div>
              <div className="font-mono">{minReadySeconds}</div>
            </div>
          )}
          {strategy?.rolling_update && (
            <div className="grid grid-cols-[160px_1fr] gap-2 text-sm mt-1">
              <div className="font-medium text-muted-foreground break-all">RollingUpdateStrategy:</div>
              <div className="font-mono">
                {strategy.rolling_update.max_unavailable || '-'} max unavailable, {strategy.rolling_update.max_surge || '-'} max surge
              </div>
            </div>
          )}
        </div>

        {/* Pod Template */}
        <div>
          <div className="font-medium text-sm mb-3">Pod Template:</div>
          <div className="pl-4 space-y-3 border-l-2">
            {/* Pod Template Labels */}
            {podTemplate?.labels && Object.keys(podTemplate.labels).length > 0 && (
              <div>
                <div className="grid grid-cols-[100px_1fr] gap-2 text-sm mb-2">
                  <div className="font-medium text-muted-foreground">Labels:</div>
                  <div className="flex flex-wrap gap-2">
                  {Object.entries(podTemplate.labels).map(([k, v]) => (
                      <Badge key={k} variant="secondary" className="font-mono">
                      {k}={String(v)}
                      </Badge>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Pod Template Annotations */}
            {podTemplate?.annotations && Object.keys(podTemplate.annotations).length > 0 && (
              <div>
                <div className="grid grid-cols-[100px_1fr] gap-2 text-sm mb-2">
                  <div className="font-medium text-muted-foreground">Annotations:</div>
                  <div className="space-y-1">
                    {Object.entries(podTemplate.annotations).map(([k, v]) => (
                      <div key={k} className="text-sm">
                        <code className="font-mono text-xs">{k}:</code>{" "}
                        <span className="font-mono text-xs">{String(v)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Containers */}
            {podTemplate?.containers && podTemplate.containers.length > 0 && (
              <div>
                <div className="font-medium text-sm mb-2">Containers:</div>
                <div className="space-y-4 pl-4">
                  {podTemplate.containers.map((container: any, idx: number) => (
                    <div key={idx} className="border rounded-lg p-3 space-y-2">
                      <div className="font-mono text-sm font-semibold">{container.name}:</div>
                      
                      <div className="grid grid-cols-[120px_1fr] gap-2 text-sm pl-4">
                        <div className="text-muted-foreground">Image:</div>
                        <div className="font-mono break-all">{container.image}</div>
                      </div>

                      {container.ports && container.ports.length > 0 && (
                        <div className="grid grid-cols-[120px_1fr] gap-2 text-sm pl-4">
                          <div className="text-muted-foreground">Port:</div>
                          <div className="space-y-1">
                            {container.ports.map((port: any, pIdx: number) => (
                              <div key={pIdx} className="font-mono">
                                {port.container_port}/{port.protocol || "TCP"}
                                {port.host_port && ` (Host Port: ${port.host_port})`}
                                {port.name && ` (name: ${port.name})`}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      <div className="grid grid-cols-[120px_1fr] gap-2 text-sm pl-4">
                        <div className="text-muted-foreground">Host Port:</div>
                        <div className="font-mono">
                          {container.ports?.find((p: any) => p.host_port)?.host_port || "0"}/TCP
                        </div>
                      </div>

                      <div className="grid grid-cols-[120px_1fr] gap-2 text-sm pl-4">
                        <div className="text-muted-foreground whitespace-nowrap">환경변수:</div>
                        <div className="font-mono whitespace-nowrap overflow-x-auto text-xs">
                          {container.env && container.env.length > 0 ? (
                            <span>
                              {container.env
                                .map((env: any) => {
                                  const valuePart = env.value_from ? "***" : (env.value || "<none>")
                                  const fromParts: string[] = []
                                  if (env.value_from?.config_map_key_ref) {
                                    fromParts.push(`from ConfigMap/${env.value_from.config_map_key_ref}`)
                                  }
                                  if (env.value_from?.secret_key_ref) {
                                    fromParts.push(`from Secret/${env.value_from.secret_key_ref}`)
                                  }
                                  const fromText = fromParts.length > 0 ? ` (${fromParts.join(', ')})` : ''
                                  return `${env.name}=${valuePart}${fromText}`
                                })
                                .join(', ')}
                            </span>
                          ) : (
                            "<none>"
                          )}
                        </div>
                      </div>

                      <div className="grid grid-cols-[120px_1fr] gap-2 text-sm pl-4">
                        <div className="text-muted-foreground">Mounts:</div>
                        <div className="font-mono">
                          {container.volume_mounts && container.volume_mounts.length > 0 ? (
                            <div className="space-y-1">
                              {container.volume_mounts.map((vm: any, vmIdx: number) => (
                                <div key={vmIdx} className="text-xs">
                                  {vm.name} from {vm.mount_path}
                                  {vm.read_only && " (read-only)"}
                                  {vm.sub_path && ` (subPath: ${vm.sub_path})`}
                                </div>
                              ))}
                            </div>
                          ) : (
                            "<none>"
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Volumes */}
            {podTemplate?.volumes && podTemplate.volumes.length > 0 && (
              <div>
                <div className="font-medium text-sm mb-2">Volumes:</div>
                <div className="pl-4 space-y-1">
                  {podTemplate.volumes.map((volume: any, idx: number) => (
                    <div key={idx} className="text-sm font-mono">
                      {volume.name} ({volume.type}
                      {volume.config_map && `: ${volume.config_map}`}
                      {volume.secret && `: ${volume.secret}`}
                      {volume.pvc && `: ${volume.pvc}`}
                      {volume.path && `: ${volume.path}`}
                      )
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Node Selectors */}
            <div>
              <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                <div className="font-medium text-muted-foreground">Node-Selectors:</div>
                <div className="font-mono">
                  {podTemplate?.node_selector && Object.keys(podTemplate.node_selector).length > 0 ? (
                    <div className="flex flex-wrap gap-2">
                      {Object.entries(podTemplate.node_selector).map(([k, v]) => (
                        <Badge key={k} variant="secondary" className="font-mono">
                          {k}={String(v)}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    "<none>"
                  )}
                </div>
              </div>
            </div>

            {/* Tolerations */}
            <div>
              <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                <div className="font-medium text-muted-foreground">Tolerations:</div>
                <div className="font-mono">
                  {podTemplate?.tolerations && podTemplate.tolerations.length > 0 ? (
                    <div className="space-y-1">
                      {podTemplate.tolerations.map((tol: any, idx: number) => (
                        <div key={idx} className="text-xs">
                          {tol.key}{tol.operator && ` ${tol.operator}`}{tol.value && ` ${tol.value}`}{tol.effect && ` ${tol.effect}`}
                          {tol.toleration_seconds && ` (for ${tol.toleration_seconds}s)`}
                        </div>
                      ))}
                    </div>
                  ) : (
                    "<none>"
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Conditions - Table format */}
        {conditions.length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">Conditions:</div>
            <div className="overflow-x-auto border rounded-lg">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/50">
                    <th className="text-left py-2 px-3 font-medium">Type</th>
                    <th className="text-left py-2 px-3 font-medium">Status</th>
                    <th className="text-left py-2 px-3 font-medium">Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {conditions.map((c: any, idx: number) => (
                    <tr key={idx} className="border-b">
                      <td className="py-2 px-3 font-mono">{c.type}</td>
                      <td className="py-2 px-3">
                        <Badge variant={c.status === "True" ? "default" : "secondary"}>
                          {c.status}
                        </Badge>
                      </td>
                      <td className="py-2 px-3">{c.reason || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Old ReplicaSets */}
        {replicaSets?.old && replicaSets.old.length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">OldReplicaSets:</div>
            <div className="pl-4 space-y-1">
              {replicaSets.old.map((rs: any, idx: number) => (
                <div key={idx} className="text-sm font-mono">
                  {rs.name} ({rs.replicas})
                </div>
              ))}
            </div>
          </div>
        )}

        {/* New ReplicaSet */}
        {replicaSets?.new && (
          <div>
            <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
              <div className="font-medium text-muted-foreground">NewReplicaSet:</div>
              <div className="font-mono">
                {replicaSets.new.name} ({replicaSets.new.replicas})
              </div>
            </div>
          </div>
        )}

        {/* Events */}
        {events && events.length > 0 && (
          <div>
            <div className="font-medium text-sm mb-2">Events:</div>
            <div className="pl-4">
              {events.length === 0 ? (
                <p className="text-sm text-muted-foreground">{"<none>"}</p>
              ) : (
                <div className="overflow-x-auto border rounded-lg">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b bg-muted/50">
                        <th className="text-left py-2 px-3 font-medium">Type</th>
                        <th className="text-left py-2 px-3 font-medium">Reason</th>
                        <th className="text-left py-2 px-3 font-medium">Message</th>
                        <th className="text-left py-2 px-3 font-medium">Count</th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.map((event: any, idx: number) => (
                        <tr key={idx} className="border-b">
                          <td className="py-2 px-3">
                            <Badge variant={event.type === "Normal" ? "default" : "destructive"}>
                              {event.type || "-"}
                            </Badge>
                          </td>
                          <td className="py-2 px-3 font-mono">{event.reason || "-"}</td>
                          <td className="py-2 px-3 text-xs">{event.message || "-"}</td>
                          <td className="py-2 px-3">{event.count || "-"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
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
