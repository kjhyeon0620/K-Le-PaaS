// 시각 파싱·표시 (#81). API는 UTC 시각에 Z를 붙여 보낸다. 화면은 브라우저 시간대와 무관하게 한국 시간으로 표시한다.

const KST = "Asia/Seoul"

// 시간대가 없는 예전 형식(배포 직후 이전 응답 등)도 UTC로 해석한다
export function parseServerTime(value: string | Date | null | undefined): Date | null {
  if (!value) return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  const date = new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`)
  return Number.isNaN(date.getTime()) ? null : date
}

function parts(date: Date, options: Intl.DateTimeFormatOptions): Record<string, string> {
  return Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", { timeZone: KST, hourCycle: "h23", ...options })
      .formatToParts(date)
      .map((part) => [part.type, part.value])
  )
}

// 2026-10-09 18:09 KST (seconds: 2026-10-09 18:09:30 KST)
export function formatKst(value: string | Date | null | undefined, withSeconds = false): string {
  const date = parseServerTime(value)
  if (!date) return "-"
  const p = parts(date, {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
    ...(withSeconds ? { second: "2-digit" } : {}),
  })
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}${withSeconds ? `:${p.second}` : ""} KST`
}

// 18:09:30 KST
export function formatKstTime(value: string | Date | null | undefined): string {
  const date = parseServerTime(value)
  if (!date) return "-"
  const p = parts(date, { hour: "2-digit", minute: "2-digit", second: "2-digit" })
  return `${p.hour}:${p.minute}:${p.second} KST`
}

// "2h ago" 형식의 경과 시간 (시간대와 무관)
export function formatTimeAgo(value: string | Date | null | undefined): string {
  const date = parseServerTime(value)
  if (!date) return "N/A"
  const seconds = Math.floor((Date.now() - date.getTime()) / 1000)
  if (seconds < 60) return "just now"
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}d ago`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months}mo ago`
  return `${Math.floor(months / 12)}y ago`
}
