"use client"

import { Suspense, useEffect, useMemo, useState } from "react"
import type { ReactNode } from "react"
import { useSearchParams } from "next/navigation"
import { CheckCircle2, Clock3, Laptop2, LogIn, ShieldX } from "lucide-react"

import api, { CliAuthSessionResponse } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"

type AuthUser = {
  id: string
  email: string
  name: string
}

function CliAuthorizePageContent() {
  const searchParams = useSearchParams()
  const sessionId = searchParams.get("session")
  const authError = searchParams.get("authError")

  const [session, setSession] = useState<CliAuthSessionResponse | null>(null)
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [acting, setActing] = useState(false)
  const [error, setError] = useState<string | null>(authError)

  useEffect(() => {
    if (!sessionId) {
      setLoading(false)
      setError("session 파라미터가 없습니다.")
      return
    }

    void loadPage(sessionId)
  }, [sessionId])

  const statusTone = useMemo(() => {
    switch (session?.status) {
      case "APPROVED":
        return "default"
      case "REJECTED":
      case "EXPIRED":
        return "destructive"
      case "CONSUMED":
        return "secondary"
      default:
        return "outline"
    }
  }, [session?.status])

  const loadPage = async (id: string) => {
    try {
      setLoading(true)
      const [sessionData, userData] = await Promise.all([
        api.getCliAuthSession(id),
        api.getCurrentUser().catch(() => null) as Promise<AuthUser | null>,
      ])
      setSession(sessionData)
      setUser(userData)
      setError(authError)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "승인 페이지를 불러오지 못했습니다.")
    } finally {
      setLoading(false)
    }
  }

  const handleLogin = async () => {
    if (!sessionId) return

    try {
      const origin = window.location.origin
      const basePath = process.env.NEXT_PUBLIC_BASE_PATH || ""
      const redirectUri = `${origin}${basePath}/auth/callback`
      const nextPath = `${basePath}/cli/authorize?session=${encodeURIComponent(sessionId)}`
      const state = new URLSearchParams({
        provider: "github",
        next: nextPath,
      }).toString()

      try {
        window.sessionStorage.setItem("oauth_login_next", nextPath)
        window.sessionStorage.setItem("oauth_login_provider", "github")
      } catch {}

      const response = await api.getOAuth2Url("github", { redirectUri, state }) as { url: string }
      window.location.assign(response.url)
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : "로그인 URL을 생성하지 못했습니다.")
    }
  }

  const handleApprove = async () => {
    if (!sessionId) return
    try {
      setActing(true)
      await api.approveCliAuthSession(sessionId)
      await loadPage(sessionId)
    } catch (approveError) {
      setError(approveError instanceof Error ? approveError.message : "승인 요청에 실패했습니다.")
    } finally {
      setActing(false)
    }
  }

  const handleReject = async () => {
    if (!sessionId) return
    try {
      setActing(true)
      await api.rejectCliAuthSession(sessionId)
      await loadPage(sessionId)
    } catch (rejectError) {
      setError(rejectError instanceof Error ? rejectError.message : "거부 요청에 실패했습니다.")
    } finally {
      setActing(false)
    }
  }

  return (
    <div className="min-h-screen bg-muted/30 flex items-center justify-center p-6">
      <Card className="w-full max-w-2xl">
        <CardHeader>
          <CardTitle>CLI 로그인 승인</CardTitle>
          <CardDescription>
            터미널에서 시작한 KLEPaaS CLI 로그인 요청을 검토하고 승인합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          {loading && <p className="text-sm text-muted-foreground">세션 정보를 불러오는 중...</p>}

          {!loading && error && (
            <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
              {error}
            </div>
          )}

          {!loading && session && (
            <>
              <div className="flex items-center justify-between gap-4 rounded-lg border p-4">
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{session.client_name}</span>
                    <Badge variant={statusTone as "default" | "destructive" | "secondary" | "outline"}>
                      {session.status}
                    </Badge>
                  </div>
                  <div className="text-sm text-muted-foreground">
                    User code: <span className="font-mono">{session.user_code}</span>
                  </div>
                </div>
                <div className="text-right text-sm text-muted-foreground">
                  만료: {formatDate(session.expires_at)}
                </div>
              </div>

              <div className="grid gap-4 md:grid-cols-3">
                <MetaCard icon={<Laptop2 className="h-4 w-4" />} label="Hostname" value={session.hostname} />
                <MetaCard icon={<Clock3 className="h-4 w-4" />} label="Platform" value={session.platform} />
                <MetaCard icon={<CheckCircle2 className="h-4 w-4" />} label="CLI Version" value={session.cli_version} />
              </div>

              <Separator />

              {!user && (
                <div className="space-y-3">
                  <div className="text-sm text-muted-foreground">
                    승인하려면 먼저 KLEPaaS 웹 계정으로 로그인해야 합니다.
                  </div>
                  <Button onClick={() => void handleLogin()}>
                    <LogIn className="mr-2 h-4 w-4" />
                    GitHub로 로그인
                  </Button>
                </div>
              )}

              {user && (
                <div className="space-y-4">
                  <div className="rounded-md border bg-background p-4 text-sm">
                    <div className="font-medium">{user.name}</div>
                    <div className="text-muted-foreground">{user.email}</div>
                  </div>

                  {session.status === "PENDING" && (
                    <div className="flex gap-3">
                      <Button onClick={() => void handleApprove()} disabled={acting}>
                        <CheckCircle2 className="mr-2 h-4 w-4" />
                        승인
                      </Button>
                      <Button variant="outline" onClick={() => void handleReject()} disabled={acting}>
                        <ShieldX className="mr-2 h-4 w-4" />
                        거부
                      </Button>
                    </div>
                  )}

                  {session.status === "APPROVED" && (
                    <div className="rounded-md border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-700">
                      승인되었습니다. 터미널로 돌아가면 CLI가 자동으로 로그인을 마칩니다.
                    </div>
                  )}

                  {session.status === "REJECTED" && (
                    <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
                      이 CLI 로그인 요청은 거부되었습니다.
                    </div>
                  )}

                  {session.status === "EXPIRED" && (
                    <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-700">
                      이 로그인 요청은 만료되었습니다. CLI에서 다시 시도하세요.
                    </div>
                  )}

                  {session.status === "CONSUMED" && (
                    <div className="rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
                      이 로그인 요청은 이미 완료되었습니다.
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

export default function CliAuthorizePage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-muted/30 flex items-center justify-center p-6 text-sm text-muted-foreground">로딩 중...</div>}>
      <CliAuthorizePageContent />
    </Suspense>
  )
}

function MetaCard({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-lg border bg-background p-4">
      <div className="mb-2 flex items-center gap-2 text-sm text-muted-foreground">
        {icon}
        {label}
      </div>
      <div className="font-medium break-all">{value}</div>
    </div>
  )
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("ko-KR")
}
