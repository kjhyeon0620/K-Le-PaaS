"use client"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

export interface OrganizationSettings {
  displayName: string
  locale: "ko" | "en"
  timezone: string
}

interface Props {
  value: OrganizationSettings
  onChange: (next: OrganizationSettings) => void
}

export function OrganizationSection({ value, onChange }: Props) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>조직 설정</CardTitle>
        <CardDescription>조직 표시 정보 및 기본 지역화 설정을 관리합니다.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="displayName">조직 표시 이름</Label>
            <Input
              id="displayName"
              placeholder="예: K-Le-PaaS 팀"
              value={value.displayName}
              onChange={(e) => onChange({ ...value, displayName: e.target.value })}
            />
          </div>
          <div className="space-y-2">
            <Label>언어</Label>
            <Select value={value.locale} onValueChange={(v: "ko" | "en") => onChange({ ...value, locale: v })}>
              <SelectTrigger>
                <SelectValue placeholder="언어를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ko">한국어</SelectItem>
                <SelectItem value="en">English</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <TimezoneSelect value={value.timezone} onChange={(tz) => onChange({ ...value, timezone: tz })} />
        </div>
      </CardContent>
    </Card>
  )
}

function TimezoneSelect({ value, onChange }: { value: string; onChange: (tz: string) => void }) {
  const TZ_OPTIONS = [
    "Asia/Seoul",
    "UTC",
    "Asia/Tokyo",
    "Asia/Shanghai",
    "Asia/Hong_Kong",
    "Asia/Singapore",
    "Europe/London",
    "Europe/Berlin",
    "America/Los_Angeles",
    "America/New_York",
  ]
  return (
    <div className="space-y-2">
      <Label>기본 시간대</Label>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger>
          <SelectValue placeholder="시간대를 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          {TZ_OPTIONS.map((tz) => (
            <SelectItem key={tz} value={tz}>
              {tz}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p className="text-xs text-muted-foreground">일반적으로 Asia/Seoul 또는 UTC를 권장합니다.</p>
    </div>
  )
}


