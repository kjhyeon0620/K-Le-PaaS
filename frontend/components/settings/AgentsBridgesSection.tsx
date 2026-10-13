"use client"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

export interface AgentRow {
  name: string
  type: string
  status: "healthy" | "degraded" | "down"
  lastHeartbeat: string
}

export interface BridgeRow {
  name: string
  kind: string
  status: "connected" | "disconnected"
}

interface Props {
  agents: AgentRow[]
  bridges: BridgeRow[]
}

export function AgentsBridgesSection({ agents, bridges }: Props) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>에이전트 & 브리지</CardTitle>
        <CardDescription>등록된 Kubernetes 에이전트와 프로토콜 브리지 상태입니다.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div>
          <div className="font-medium mb-2">에이전트</div>
          <div className="space-y-2">
            {agents.map((a) => (
              <div key={a.name} className="flex items-center justify-between p-3 border rounded-md">
                <div>
                  <div className="font-medium">{a.name}</div>
                  <div className="text-xs text-muted-foreground">{a.type} · 마지막 하트비트 {a.lastHeartbeat}</div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant={a.status === "healthy" ? "default" : a.status === "degraded" ? "secondary" : "secondary"}>
                    {a.status === "healthy" ? "정상" : a.status === "degraded" ? "주의" : "오프라인"}
                  </Badge>
                  <Button variant="ghost" size="sm">상태 확인</Button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <div className="font-medium mb-2">브리지</div>
          <div className="space-y-2">
            {bridges.map((b) => (
              <div key={b.name} className="flex items-center justify-between p-3 border rounded-md">
                <div>
                  <div className="font-medium">{b.name}</div>
                  <div className="text-xs text-muted-foreground">{b.kind}</div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant={b.status === "connected" ? "default" : "secondary"}>
                    {b.status === "connected" ? "연결됨" : "미연결"}
                  </Badge>
                  <Button variant="ghost" size="sm">연결 테스트</Button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}


