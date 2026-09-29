"use client"

import { useEffect, useState } from "react"
import { Copy, KeyRound, Trash2 } from "lucide-react"

import api, { CLI_TOKEN_SCOPE_LABELS, CliAccessTokenResponse, CliTokenScope, CreateCliAccessTokenResponse } from "@/lib/api"
import { useToast } from "@/hooks/use-toast"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"

export function CliTokensSection() {
  const { toast } = useToast()
  const [tokens, setTokens] = useState<CliAccessTokenResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [newToken, setNewToken] = useState<CreateCliAccessTokenResponse | null>(null)
  const [name, setName] = useState("local-cli")
  const [expiresInDays, setExpiresInDays] = useState("30")
  // 자동화·에이전트에는 조회 전용을 기본으로 권장한다
  const [scope, setScope] = useState<CliTokenScope>("READ_ONLY")
  const [repositoryId, setRepositoryId] = useState("")
  const [repositories, setRepositories] = useState<{ id: number; fullName: string }[]>([])

  useEffect(() => {
    void loadTokens()
    // 배포 전용 토큰의 대상 저장소 선택용
    api.getProjectIntegrations()
      .then((data) => setRepositories((data.repositories || []).map((r: any) => ({ id: r.id, fullName: r.fullName }))))
      .catch(() => setRepositories([]))
  }, [])

  const loadTokens = async () => {
    try {
      setLoading(true)
      const data = await api.getCliAccessTokens()
      setTokens(data)
    } catch (error) {
      toast({
        title: "CLI 토큰 조회 실패",
        description: error instanceof Error ? error.message : "토큰 목록을 불러오지 못했습니다.",
        variant: "destructive",
      })
    } finally {
      setLoading(false)
    }
  }

  const createToken = async () => {
    const parsedDays = Number(expiresInDays)
    if (!name.trim() || !Number.isFinite(parsedDays) || parsedDays < 1 || parsedDays > 365) {
      toast({
        title: "입력 오류",
        description: "토큰 이름과 만료일(1~365일)을 확인하세요.",
        variant: "destructive",
      })
      return
    }

    if (scope === "DEPLOY" && !repositoryId) {
      toast({ title: "입력 오류", description: "배포 전용 토큰은 대상 저장소를 선택해야 합니다.", variant: "destructive" })
      return
    }

    try {
      setCreating(true)
      const created = await api.createCliAccessToken({
        name: name.trim(),
        expiresInDays: parsedDays,
        scope,
        repositoryId: scope === "DEPLOY" ? Number(repositoryId) : undefined,
      })
      setNewToken(created)
      setTokens((current) => [created.metadata, ...current])
      toast({
        title: "CLI 토큰 발급 완료",
        description: "아래 토큰은 지금 한 번만 확인할 수 있습니다.",
      })
    } catch (error) {
      toast({
        title: "CLI 토큰 발급 실패",
        description: error instanceof Error ? error.message : "토큰 발급 중 오류가 발생했습니다.",
        variant: "destructive",
      })
    } finally {
      setCreating(false)
    }
  }

  const revokeToken = async (tokenId: number) => {
    try {
      await api.revokeCliAccessToken(tokenId)
      setTokens((current) =>
        current.map((token) =>
          token.id === tokenId ? { ...token, revoked_at: new Date().toISOString() } : token
        )
      )
      toast({ title: "CLI 토큰 폐기 완료" })
    } catch (error) {
      toast({
        title: "CLI 토큰 폐기 실패",
        description: error instanceof Error ? error.message : "토큰 폐기 중 오류가 발생했습니다.",
        variant: "destructive",
      })
    }
  }

  const copyToken = async () => {
    if (!newToken?.token) return
    await navigator.clipboard.writeText(newToken.token)
    toast({ title: "토큰 복사 완료" })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>CLI Tokens</CardTitle>
        <CardDescription>
          CLI, 스크립트, 에이전트용 전용 액세스 토큰을 발급합니다. 조회 전용은 조회·비용 계산만, 제안 전용은 여기에 자연어 명령 제안이 더해지며(실행은 사람이 승인), 배포 전용은 선택한 저장소의 배포 요청만 할 수 있습니다. 그 밖의 요청은 서버에서 거부됩니다. 토큰 원문은 발급 직후 한 번만 표시됩니다.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="grid gap-4 md:grid-cols-[1.5fr,120px,160px,auto]">
          <div className="space-y-2">
            <Label htmlFor="cli-token-name">토큰 이름</Label>
            <Input
              id="cli-token-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: local-cli, jenkins-runner"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cli-token-expiry">만료일(일)</Label>
            <Input
              id="cli-token-expiry"
              type="number"
              min={1}
              max={365}
              value={expiresInDays}
              onChange={(e) => setExpiresInDays(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cli-token-scope">권한</Label>
            <select
              id="cli-token-scope"
              value={scope}
              onChange={(e) => setScope(e.target.value as CliTokenScope)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs"
            >
              {(Object.keys(CLI_TOKEN_SCOPE_LABELS) as CliTokenScope[]).map((value) => (
                <option key={value} value={value}>{CLI_TOKEN_SCOPE_LABELS[value]}</option>
              ))}
            </select>
          </div>
          {scope === "DEPLOY" && (
            <div className="space-y-2 md:col-span-3">
              <Label htmlFor="cli-token-repository">배포 대상 저장소</Label>
              <select
                id="cli-token-repository"
                value={repositoryId}
                onChange={(e) => setRepositoryId(e.target.value)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs"
              >
                <option value="">저장소 선택</option>
                {repositories.map((repo) => (
                  <option key={repo.id} value={repo.id}>{repo.fullName}</option>
                ))}
              </select>
            </div>
          )}
          <div className="flex items-end">
            <Button onClick={createToken} disabled={creating}>
              <KeyRound className="mr-2 h-4 w-4" />
              {creating ? "발급 중..." : "토큰 발급"}
            </Button>
          </div>
        </div>

        {newToken && (
          <div className="rounded-lg border border-amber-300 bg-amber-50 p-4 space-y-3">
            <div className="flex items-center justify-between gap-3">
              <div>
                <div className="font-medium text-amber-900">새 CLI 토큰</div>
                <p className="text-sm text-amber-800">이 값은 다시 볼 수 없습니다. 지금 복사해 두세요.</p>
              </div>
              <Button variant="outline" onClick={copyToken}>
                <Copy className="mr-2 h-4 w-4" />
                복사
              </Button>
            </div>
            <code className="block rounded bg-black/90 p-3 text-sm text-green-300 break-all">{newToken.token}</code>
          </div>
        )}

        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="font-medium">발급된 토큰</div>
            <Button variant="ghost" size="sm" onClick={() => void loadTokens()} disabled={loading}>
              새로고침
            </Button>
          </div>

          <div className="space-y-2">
            {tokens.length === 0 && !loading && (
              <div className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
                아직 발급된 CLI 토큰이 없습니다.
              </div>
            )}

            {tokens.map((token) => {
              const revoked = Boolean(token.revoked_at)
              return (
                <div key={token.id} className="flex items-center justify-between gap-4 rounded-md border p-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{token.name}</span>
                      <Badge variant={revoked ? "secondary" : "default"}>
                        {revoked ? "폐기됨" : "활성"}
                      </Badge>
                      <Badge variant="outline">
                        {CLI_TOKEN_SCOPE_LABELS[token.scope]}
                        {token.repository_id ? ` · 저장소 #${token.repository_id}` : ""}
                      </Badge>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Prefix: {token.token_prefix} | 생성: {formatDate(token.created_at)} | 만료: {formatDate(token.expires_at)}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      마지막 사용: {token.last_used_at ? formatDate(token.last_used_at) : "없음"}
                    </div>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={revoked}
                    onClick={() => void revokeToken(token.id)}
                  >
                    <Trash2 className="mr-2 h-4 w-4" />
                    폐기
                  </Button>
                </div>
              )
            })}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("ko-KR")
}
