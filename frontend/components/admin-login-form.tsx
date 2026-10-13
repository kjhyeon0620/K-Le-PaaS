"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useAuth } from "@/contexts/auth-context"
import { apiClient } from "@/lib/api"

interface AdminLoginFormProps {
  isOpen: boolean
  onClose: () => void
  onBack: () => void
}

interface AdminLoginResponse {
  success: boolean
  access_token: string
  user: {
    id: string
    provider_id: string
    email: string
    name: string
    picture?: string
    provider: string
  }
  message: string
}

export function AdminLoginForm({ isOpen, onClose, onBack }: AdminLoginFormProps) {
  const { login } = useAuth()
  const [isLoading, setIsLoading] = useState(false)
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState("")

  if (!isOpen) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError("")
    setIsLoading(true)

    try {
      const response = await apiClient.adminLogin({ username, password }) as AdminLoginResponse

      if (response.success) {
        login(
          {
            id: response.user.id,
            provider_id: response.user.provider_id,
            email: response.user.email,
            name: response.user.name,
            picture: response.user.picture,
            provider: 'admin'
          },
          response.access_token
        )
        onClose()
      }
    } catch (err) {
      console.error('Admin 로그인 실패:', err)
      setError("로그인에 실패했습니다. 사용자명과 비밀번호를 확인해주세요.")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Admin 로그인</CardTitle>
          <CardDescription>
            관리자 계정으로 로그인하세요.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">사용자명</Label>
              <Input
                id="username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="admin"
                disabled={isLoading}
                required
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="password">비밀번호</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="비밀번호를 입력하세요"
                disabled={isLoading}
                required
              />
            </div>

            {error && (
              <div className="text-sm text-red-500">
                {error}
              </div>
            )}

            <Button
              type="submit"
              disabled={isLoading}
              className="w-full"
            >
              {isLoading ? "로그인 중..." : "로그인"}
            </Button>

            <Button
              type="button"
              onClick={onBack}
              variant="ghost"
              className="w-full"
            >
              뒤로
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
