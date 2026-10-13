"use client"

import { Suspense, useEffect } from 'react'
import { useSearchParams } from 'next/navigation'
import { config } from '@/lib/config'

function CallbackInner() {
  const searchParams = useSearchParams()

  useEffect(() => {
    const code = searchParams.get('code')
    const error = searchParams.get('error')
    const state = searchParams.get('state')
    const provider = state && state.includes('provider=')
      ? (state.split('provider=')[1] as string)
      : 'github'

    if (error) {
      window.opener?.postMessage({
        type: 'OAUTH2_ERROR',
        error: error,
      }, window.location.origin)
      window.close()
      return
    }

    if (code) {
      handleOAuth2Callback(code, provider as 'github')
    }
  }, [searchParams])

  const handleOAuth2Callback = async (code: string, provider: 'github') => {
    try {
      const origin = window.location.origin
      const redirectUri = `${origin}${config.app.basePath}/oauth2-callback`

      // Exchange code for tokens (backend returns ApiResponse wrapper)
      const tokenResponse = await fetch(`${config.api.baseUrl}/api/v1/auth/oauth2/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code }),
      })

      if (!tokenResponse.ok) {
        throw new Error(`HTTP error! status: ${tokenResponse.status}`)
      }

      const tokenJson = await tokenResponse.json()
      // tokenJson = { status: "success", data: { access_token, refresh_token } }
      const tokenData = tokenJson?.data || tokenJson

      if (!tokenData?.access_token) {
        throw new Error(tokenJson?.message || '로그인 실패')
      }

      // Fetch user info with the new token
      const meResponse = await fetch(`${config.api.baseUrl}/api/v1/auth/me`, {
        headers: {
          'Authorization': `Bearer ${tokenData.access_token}`,
          'Content-Type': 'application/json',
        },
      })

      if (!meResponse.ok) {
        throw new Error('사용자 정보 조회 실패')
      }

      const meJson = await meResponse.json()
      const userData = meJson?.data || meJson

      const userInfo = {
        id: String(userData?.id || ''),
        email: userData?.email || '',
        name: userData?.name || '',
        provider: 'github' as const,
      }

      window.opener?.postMessage({
        type: 'OAUTH2_SUCCESS',
        user: userInfo,
        accessToken: tokenData.access_token,
      }, window.location.origin)

      window.close()
    } catch (error) {
      console.error('OAuth2 콜백 처리 실패:', error)
      window.opener?.postMessage({
        type: 'OAUTH2_ERROR',
        error: error instanceof Error ? error.message : '알 수 없는 오류',
      }, window.location.origin)
      window.close()
    }
  }

  return (
    <div className="flex items-center justify-center min-h-screen">
      <div className="text-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mx-auto mb-4"></div>
        <p>로그인 처리 중...</p>
      </div>
    </div>
  )
}

export default function OAuth2CallbackPage() {
  return (
    <Suspense fallback={<div className="flex items-center justify-center min-h-screen"><p>로그인 처리 중...</p></div>}>
      <CallbackInner />
    </Suspense>
  )
}
