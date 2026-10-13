"use client"

import { Suspense, useEffect } from "react"
import { useSearchParams } from "next/navigation"
import { apiClient } from "@/lib/api"

function CallbackInner() {
  const searchParams = useSearchParams()

  useEffect(() => {
    const handleOAuthCallback = async () => {
      const code = searchParams.get('code')
      const state = searchParams.get('state')
      const stateParams = new URLSearchParams(state || '')
      const storedNextPath = safeSessionGet('oauth_login_next')
      const nextPath = stateParams.get('next') || storedNextPath

      const storedProvider = safeSessionGet('oauth_login_provider')
      const provider = ((stateParams.get('provider') || storedProvider || 'github')) as 'github'

      if (!code) {
        handleError('Missing authorization code in OAuth2 callback', nextPath)
        return
      }

      try {
        const origin = window.location.origin
        const basePath = process.env.NEXT_PUBLIC_BASE_PATH || ''
        const redirectUri = `${origin}${basePath}/auth/callback`

        const tokenData = await apiClient.loginWithOAuth2(
          provider as 'github',
          code,
          redirectUri
        ) as any

        if (!tokenData?.access_token) {
          throw new Error('Login failed — no access token received')
        }

        // Store token temporarily so getCurrentUser() can use it
        localStorage.setItem('auth_token', tokenData.access_token)

        // Fetch user info with the new token
        const userData = await apiClient.getCurrentUser() as any

        const userInfo = {
          id: String(userData?.id || ''),
          email: userData?.email || '',
          name: userData?.name || '',
          provider: 'github' as const,
        }

        if (window.opener && !nextPath) {
          window.opener.postMessage({
            type: 'OAUTH2_SUCCESS',
            user: userInfo,
            accessToken: tokenData.access_token,
          }, window.location.origin)
          window.close()
          return
        }

        if (nextPath) {
          safeSessionClear()
          window.location.replace(nextPath)
          return
        }

        safeSessionClear()
        window.location.replace('/')
      } catch (error) {
        console.error('OAuth2 callback error:', error)
        handleError((error as Error).message || 'OAuth2 callback failed', nextPath)
      }
    }

    handleOAuthCallback()
  }, [searchParams])

  return (
    <div className="flex items-center justify-center min-h-screen bg-gray-100">
      <p>로그인 처리 중...</p>
    </div>
  )
}

function handleError(message: string, nextPath: string | null) {
  safeSessionClear()
  if (window.opener && !nextPath) {
    window.opener.postMessage({
      type: 'OAUTH2_ERROR',
      error: message,
    }, window.location.origin)
    window.close()
    return
  }

  const target = nextPath
    ? `${nextPath}${nextPath.includes('?') ? '&' : '?'}authError=${encodeURIComponent(message)}`
    : `/?authError=${encodeURIComponent(message)}`
  window.location.replace(target)
}

function safeSessionGet(key: string) {
  try {
    return window.sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSessionClear() {
  try {
    window.sessionStorage.removeItem('oauth_login_next')
    window.sessionStorage.removeItem('oauth_login_provider')
  } catch {}
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="flex items-center justify-center min-h-screen bg-gray-100"><p>로그인 처리 중...</p></div>}>
      <CallbackInner />
    </Suspense>
  )
}
