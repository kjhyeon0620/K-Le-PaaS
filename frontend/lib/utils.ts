import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// 시각 표시는 lib/time.ts에 모은다 (#81)
export { formatTimeAgo } from './time'

/**
 * 초 단위 시간을 "2m 7s" 형식으로 변환
 * 예: 127 → "2m 7s", 65 → "1m 5s", 45 → "45s"
 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return 'N/A'
  if (seconds < 0) return 'N/A'

  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60

  if (mins === 0) {
    return `${secs}s`
  }

  return `${mins}m ${secs}s`
}
