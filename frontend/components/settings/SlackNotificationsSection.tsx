"use client"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"

export interface SlackSettings {
  enabled: boolean
  webhookUrl: string
  events: {
    deployStart: boolean
    deploySuccess: boolean
    deployFail: boolean
    rollback: boolean
  }
}

interface Props {
  value: SlackSettings
  onChange: (next: SlackSettings) => void
}

export function SlackNotificationsSection({ value, onChange }: Props) {
  const set = (patch: Partial<SlackSettings>) => onChange({ ...value, ...patch })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Slack 알림</CardTitle>
        <CardDescription>배포 이벤트에 대한 Slack 알림을 구성합니다. 실제 연동은 포함되지 않습니다.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <Label>알림 사용</Label>
            <p className="text-xs text-muted-foreground">토글을 켜면 아래 이벤트 설정이 적용됩니다.</p>
          </div>
          <Switch checked={value.enabled} onCheckedChange={(v) => set({ enabled: v })} />
        </div>

        <div className="space-y-2">
          <Label htmlFor="slackWebhook">Webhook URL</Label>
          <Input
            id="slackWebhook"
            placeholder="https://hooks.slack.com/services/..."
            value={value.webhookUrl}
            onChange={(e) => set({ webhookUrl: e.target.value })}
          />
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <ToggleRow
            label="배포 시작"
            checked={value.events.deployStart}
            onChange={(v) => set({ events: { ...value.events, deployStart: v } })}
          />
          <ToggleRow
            label="배포 성공"
            checked={value.events.deploySuccess}
            onChange={(v) => set({ events: { ...value.events, deploySuccess: v } })}
          />
          <ToggleRow
            label="배포 실패"
            checked={value.events.deployFail}
            onChange={(v) => set({ events: { ...value.events, deployFail: v } })}
          />
          <ToggleRow
            label="롤백"
            checked={value.events.rollback}
            onChange={(v) => set({ events: { ...value.events, rollback: v } })}
          />
        </div>
      </CardContent>
    </Card>
  )
}

function ToggleRow({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-center justify-between p-3 border rounded-md">
      <span className="text-sm">{label}</span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  )
}


