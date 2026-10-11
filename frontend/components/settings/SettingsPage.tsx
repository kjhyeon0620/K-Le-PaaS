"use client"

import { useEffect, useMemo, useState } from "react"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { PreviewNotice } from "@/components/preview-notice"
import { useToast } from "@/hooks/use-toast"
import { SettingsHeader } from "./SettingsHeader"
import { OrganizationSection, OrganizationSettings } from "./OrganizationSection"
import { EnvironmentsClustersSection, EnvironmentItem } from "./EnvironmentsClustersSection"
import { SlackNotificationsSection, SlackSettings } from "./SlackNotificationsSection"
import { MCPConnectorsSection, MCPSettings } from "./MCPConnectorsSection"
import { AgentsBridgesSection, AgentRow, BridgeRow } from "./AgentsBridgesSection"
import { CliTokensSection } from "./CliTokensSection"

type SettingsDraft = {
  organization: OrganizationSettings
  environments: EnvironmentItem[]
  slack: SlackSettings
  mcp: MCPSettings
}

const STORAGE_KEY = "kpaas_settings_draft"
const PREVIEW_TEXT = "백엔드와 연결되지 않은 미리보기로, 저장하면 이 브라우저에만 남고 서버 동작에는 영향이 없습니다."

const DEFAULT_DRAFT: SettingsDraft = {
  organization: { displayName: "K-Le-PaaS", locale: "ko", timezone: "Asia/Seoul" },
  environments: [
    { name: "staging", cluster: "GCP-Staging", namespace: "default", cpuQuota: "1", memQuota: "1Gi" },
    { name: "production", cluster: "NCP-Prod", namespace: "prod", cpuQuota: "2", memQuota: "2Gi" },
  ],
  slack: {
    enabled: false,
    webhookUrl: "",
    events: { deployStart: true, deploySuccess: true, deployFail: true, rollback: true },
  },
  mcp: { github: { connected: false }, claude: { enabled: false }, openai: { enabled: false } },
}

const MOCK_AGENTS: AgentRow[] = [
  { name: "ncp-agent", type: "NCP", status: "healthy", lastHeartbeat: "방금 전" },
  { name: "gcp-agent", type: "GCP", status: "healthy", lastHeartbeat: "1분 전" },
]

const MOCK_BRIDGES: BridgeRow[] = [
  { name: "rabbitmq-bridge", kind: "RabbitMQ", status: "connected" },
  { name: "prometheus-bridge", kind: "Prometheus", status: "connected" },
]

export function SettingsPage() {
  const { toast } = useToast()
  const [draft, setDraft] = useState<SettingsDraft>(DEFAULT_DRAFT)
  const [initial, setInitial] = useState<SettingsDraft | null>(null)

  // Load from localStorage
  useEffect(() => {
    try {
      const raw = typeof window !== "undefined" ? window.localStorage.getItem(STORAGE_KEY) : null
      if (raw) {
        const parsed = JSON.parse(raw) as SettingsDraft
        setDraft(parsed)
        setInitial(parsed)
      } else {
        setInitial(DEFAULT_DRAFT)
      }
    } catch {
      setInitial(DEFAULT_DRAFT)
    }
  }, [])

  const dirty = useMemo(() => JSON.stringify(draft) !== JSON.stringify(initial), [draft, initial])

  // Unsaved changes guard
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (!dirty) return
      e.preventDefault()
      e.returnValue = "이 페이지를 떠나면 변경 사항이 사라집니다."
    }
    window.addEventListener("beforeunload", handler)
    return () => window.removeEventListener("beforeunload", handler)
  }, [dirty])

  const handleSave = () => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(draft))
      setInitial(draft)
      toast({ title: "설정이 저장되었습니다", description: "데모 저장소(localStorage)에 저장했습니다." })
    } catch {
      toast({ title: "저장 실패", description: "브라우저 저장소에 접근할 수 없습니다.", variant: "destructive" })
    }
  }

  const handleCancel = () => {
    if (initial) setDraft(initial)
  }

  return (
    <div className="space-y-6">
      <SettingsHeader
        title="Settings"
        subtitle="대부분의 설정 섹션은 프론트엔드 데모이며 브라우저에만 임시 저장됩니다. 단, CLI 토큰 섹션은 실제 백엔드 API와 연동됩니다."
        onSave={handleSave}
        onCancel={handleCancel}
        saveDisabled={!dirty}
      />

      {/* CLI 토큰과 MCP 안내만 실제 기능이다. 나머지 탭은 백엔드 없이 이 브라우저에만 저장되는 미리보기다 (#59) */}
      <Tabs defaultValue="cli" className="w-full">
        <TabsList>
          <TabsTrigger value="cli">CLI 토큰</TabsTrigger>
          <TabsTrigger value="mcp">MCP 연결</TabsTrigger>
          <TabsTrigger value="organization">조직 (준비 중)</TabsTrigger>
          <TabsTrigger value="env">환경 & 클러스터 (준비 중)</TabsTrigger>
          <TabsTrigger value="slack">Slack 알림 (준비 중)</TabsTrigger>
          <TabsTrigger value="agents">에이전트 & 브리지 (준비 중)</TabsTrigger>
        </TabsList>

        <TabsContent value="organization" className="mt-4 space-y-4">
          <PreviewNotice>{PREVIEW_TEXT}</PreviewNotice>
          <OrganizationSection value={draft.organization} onChange={(v) => setDraft({ ...draft, organization: v })} />
        </TabsContent>

        <TabsContent value="env" className="mt-4 space-y-4">
          <PreviewNotice>{PREVIEW_TEXT}</PreviewNotice>
          <EnvironmentsClustersSection
            value={draft.environments}
            onChange={(v) => setDraft({ ...draft, environments: v })}
          />
        </TabsContent>

        <TabsContent value="slack" className="mt-4 space-y-4">
          <PreviewNotice>{PREVIEW_TEXT} 배포 알림은 서버 환경 변수 SLACK_WEBHOOK_URL로 설정합니다.</PreviewNotice>
          <SlackNotificationsSection value={draft.slack} onChange={(v) => setDraft({ ...draft, slack: v })} />
        </TabsContent>

        <TabsContent value="cli" className="mt-4">
          <CliTokensSection />
        </TabsContent>

        <TabsContent value="mcp" className="mt-4 space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>K-Le-PaaS MCP 서버</CardTitle>
              <CardDescription>Claude Code·Codex 같은 AI 에이전트가 배포 조회·진단·복구 계획·복구 제안 도구를 쓰도록 연결합니다. 승인·실행은 사람만 합니다.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <p>주소: <code className="font-mono">{"<API 주소>/api/v1/mcp"}</code> (Streamable HTTP)</p>
              <p>인증: CLI 토큰 탭에서 <b>제안 전용(propose-only)</b> 토큰을 발급해 <code className="font-mono">Authorization: Bearer</code>로 보냅니다.</p>
              <p className="text-muted-foreground">도구 목록과 클라이언트 설정 예시는 저장소의 docs/MCP.md에 있습니다.</p>
            </CardContent>
          </Card>
          <PreviewNotice>아래 외부 MCP 커넥터 관리는 {PREVIEW_TEXT}</PreviewNotice>
          <MCPConnectorsSection value={draft.mcp} onChange={(v) => setDraft({ ...draft, mcp: v })} />
        </TabsContent>

        <TabsContent value="agents" className="mt-4 space-y-4">
          <PreviewNotice>{PREVIEW_TEXT} 표의 값은 예시입니다.</PreviewNotice>
          <AgentsBridgesSection agents={MOCK_AGENTS} bridges={MOCK_BRIDGES} />
        </TabsContent>
      </Tabs>
    </div>
  )
}


