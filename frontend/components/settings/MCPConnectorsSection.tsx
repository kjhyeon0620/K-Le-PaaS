"use client"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Switch } from "@/components/ui/switch"

export interface MCPSettings {
  github: { connected: boolean }
  claude: { enabled: boolean }
  openai: { enabled: boolean }
}

interface Props {
  value: MCPSettings
  onChange: (next: MCPSettings) => void
}

export function MCPConnectorsSection({ value, onChange }: Props) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>MCP 커넥터</CardTitle>
        <CardDescription>외부 MCP 서버 연동 상태를 표시하고 사용 여부를 제어합니다.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <Row
          title="GitHub MCP"
          description="리포지토리 자동화"
          status={value.github.connected ? "연결됨" : "미연결"}
          statusIntent={value.github.connected ? "success" : "secondary"}
          right={
            <Badge variant={value.github.connected ? "default" : "secondary"}>
              {value.github.connected ? "연결됨" : "미연결"}
            </Badge>
          }
        />
        <Row
          title="Claude MCP"
          description="자연어 처리 도우미"
          status={value.claude.enabled ? "사용" : "미사용"}
          statusIntent={value.claude.enabled ? "success" : "secondary"}
          right={<Switch checked={value.claude.enabled} onCheckedChange={(v) => onChange({ ...value, claude: { enabled: v } })} />}
        />
        <Row
          title="OpenAI MCP"
          description="대체 모델 사용"
          status={value.openai.enabled ? "사용" : "미사용"}
          statusIntent={value.openai.enabled ? "success" : "secondary"}
          right={<Switch checked={value.openai.enabled} onCheckedChange={(v) => onChange({ ...value, openai: { enabled: v } })} />}
        />
      </CardContent>
    </Card>
  )
}

function Row({
  title,
  description,
  status,
  statusIntent,
  right,
}: {
  title: string
  description: string
  status: string
  statusIntent: "success" | "secondary"
  right?: React.ReactNode
}) {
  return (
    <div className="flex items-center justify-between p-4 border rounded-md">
      <div>
        <div className="font-medium">{title}</div>
        <div className="text-xs text-muted-foreground">{description}</div>
      </div>
      <div className="flex items-center gap-3">
        <Badge variant={statusIntent === "success" ? "default" : "secondary"}>{status}</Badge>
        {right}
      </div>
    </div>
  )
}


