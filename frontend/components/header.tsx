"use client"

import { useState, useEffect } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { Bell, Search, User, LogOut, Plus } from "lucide-react"
import { useAuth } from "@/contexts/auth-context"
import { LoginModal } from "@/components/login-modal"

export function Header() {
  const { user, logout } = useAuth()
  const [isLoginModalOpen, setIsLoginModalOpen] = useState(false)

  // 다른 컴포넌트에서 로그인 모달을 열 수 있도록 이벤트 리스너 추가
  useEffect(() => {
    const handleOpenLoginModal = () => {
      setIsLoginModalOpen(true)
    }
    
    window.addEventListener('openLoginModal', handleOpenLoginModal)
    
    return () => {
      window.removeEventListener('openLoginModal', handleOpenLoginModal)
    }
  }, [])

  const handleUserClick = () => {
    if (user) {
      // 로그인된 경우: 사용자 정보 표시 (아직 구현 안함)
      console.log('사용자 정보:', user)
    } else {
      // 로그인 안된 경우: 로그인 모달 열기
      setIsLoginModalOpen(true)
    }
  }

  const handleLogout = () => {
    logout()
  }

  return (
    <>
      <header className="bg-card border-b border-border px-4 h-16 flex items-center">
        <div className="flex items-center justify-between w-full">
          <div className="flex items-center space-x-4">
            <h1 className="text-2xl font-bold text-foreground">K-Le-PaaS</h1>
            <Badge variant="secondary" className="bg-secondary text-secondary-foreground">
              NCP Connected
            </Badge>
          </div>

          <div className="flex items-center space-x-4">
            {/* Search */}
            <div className="relative">
              <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground w-4 h-4" />
              <Input placeholder="Search resources..." className="pl-10 w-64" />
            </div>

            {/* Notifications */}
            <Button variant="ghost" size="sm" className="relative">
              <Bell className="w-4 h-4" />
              <Badge className="absolute -top-1 -right-1 w-2 h-2 p-0 bg-destructive" />
            </Button>

            {/* User */}
            <div className="flex items-center space-x-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={handleUserClick}
                className={`flex items-center gap-2 ${!user ? 'border border-dashed border-muted-foreground/30 hover:border-muted-foreground/50' : ''}`}
              >
                {user ? (
                  <Avatar className="w-6 h-6">
                    <AvatarImage src={user.picture} alt={user.name} />
                    <AvatarFallback className="bg-primary text-primary-foreground text-[10.5px] font-semibold">
                      K5s
                    </AvatarFallback>
                  </Avatar>
                ) : (
                  <div className="relative">
                    <User className="w-4 h-4" />
                    <Plus className="w-2 h-2 absolute -top-1 -right-1 bg-primary text-primary-foreground rounded-full p-0.5" />
                  </div>
                )}
                {!user && <span className="text-sm text-muted-foreground">로그인</span>}
              </Button>

              {user && (
                <Button variant="ghost" size="sm" onClick={handleLogout}>
                  <LogOut className="w-4 h-4" />
                </Button>
              )}
            </div>
          </div>
        </div>
      </header>

      <LoginModal 
        isOpen={isLoginModalOpen} 
        onClose={() => setIsLoginModalOpen(false)} 
      />
    </>
  )
}
