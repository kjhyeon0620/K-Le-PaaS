"use client"

import { useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"

export interface EnvironmentItem {
  name: string
  cluster: string
  namespace: string
  cpuQuota: string
  memQuota: string
}

interface Props {
  value: EnvironmentItem[]
  onChange: (next: EnvironmentItem[]) => void
}

const CLUSTERS = ["NCP-Prod", "GCP-Staging", "Local-Demo"]

export function EnvironmentsClustersSection({ value, onChange }: Props) {
  const [items, setItems] = useState<EnvironmentItem[]>(value)

  const addEnv = () => {
    const next = [...items, { name: "staging", cluster: "GCP-Staging", namespace: "default", cpuQuota: "1", memQuota: "1Gi" }]
    setItems(next)
    onChange(next)
  }

  const update = (i: number, patch: Partial<EnvironmentItem>) => {
    const next = items.map((it, idx) => (idx === i ? { ...it, ...patch } : it))
    setItems(next)
    onChange(next)
  }

  const remove = (i: number) => {
    const next = items.filter((_, idx) => idx !== i)
    setItems(next)
    onChange(next)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>환경 & 클러스터</CardTitle>
        <CardDescription>환경을 정의하고 각 환경을 클러스터 및 네임스페이스에 매핑합니다.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {items.map((env, i) => (
          <div key={`${env.name}-${i}`} className="space-y-3 p-4 border rounded-md">
            <div className="grid grid-cols-1 md:grid-cols-5 gap-3 items-end">
              <div className="space-y-2">
                <Label>환경 이름</Label>
                <Input value={env.name} onChange={(e) => update(i, { name: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label>클러스터</Label>
                <Select value={env.cluster} onValueChange={(v) => update(i, { cluster: v })}>
                  <SelectTrigger>
                    <SelectValue placeholder="클러스터 선택" />
                  </SelectTrigger>
                  <SelectContent>
                    {CLUSTERS.map((c) => (
                      <SelectItem key={c} value={c}>
                        {c}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>네임스페이스</Label>
                <Input value={env.namespace} onChange={(e) => update(i, { namespace: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label>CPU 쿼터</Label>
                <Input value={env.cpuQuota} onChange={(e) => update(i, { cpuQuota: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label>메모리 쿼터</Label>
                <Input value={env.memQuota} onChange={(e) => update(i, { memQuota: e.target.value })} />
              </div>
            </div>
            <div className="flex justify-end">
              <Button variant="ghost" onClick={() => remove(i)}>
                삭제
              </Button>
            </div>
          </div>
        ))}
        <Separator />
        <Button variant="secondary" onClick={addEnv}>
          환경 추가
        </Button>
      </CardContent>
    </Card>
  )
}


