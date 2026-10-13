"use client"

import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"

interface SettingsHeaderProps {
  title: string
  subtitle?: string
  onSave?: () => void
  onCancel?: () => void
  saveDisabled?: boolean
}

export function SettingsHeader({ title, subtitle, onSave, onCancel, saveDisabled }: SettingsHeaderProps) {
  return (
    <div className="mb-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold leading-tight">{title}</h1>
          {subtitle && <p className="text-sm text-muted-foreground mt-1">{subtitle}</p>}
        </div>
        <div className="space-x-2">
          {onCancel && (
            <Button variant="ghost" onClick={onCancel}>
              취소
            </Button>
          )}
          {onSave && (
            <Button onClick={onSave} disabled={saveDisabled}>
              저장
            </Button>
          )}
        </div>
      </div>
      <Separator className="mt-4" />
    </div>
  )
}


