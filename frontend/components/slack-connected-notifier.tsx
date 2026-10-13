'use client'

import { useEffect } from 'react'
import { useToast } from '@/hooks/use-toast'

export default function SlackConnectedNotifier() {
  const { toast } = useToast()

  useEffect(() => {
    try {
      const search = typeof window !== 'undefined' ? new URLSearchParams(window.location.search) : undefined
      if (search?.get('slack') === 'connected') {
        // 살짝 지연하여 Toaster가 확실히 마운트된 뒤 표시
        setTimeout(() => {
          try {
            toast({ title: 'Slack 연동 완료', description: '이제 배포 알림을 Slack에서 받아요.', duration: 3000 })
          } catch {
            // eslint-disable-next-line no-alert
            alert('Slack 연동이 완료되었습니다.')
          }
        }, 50)
        const url = new URL(window.location.href)
        url.searchParams.delete('slack')
        window.history.replaceState({}, '', url.toString())
      }
    } catch {}
  }, [])

  return null
}


