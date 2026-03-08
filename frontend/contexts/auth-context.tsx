"use client"

import React, { createContext, useContext, useState, useEffect } from 'react'
import { apiClient } from '@/lib/api'

interface User {
  id: string
  provider_id?: string  // OAuth provider의 실제 고유 ID
  email: string
  name: string
  picture?: string
  provider: 'google' | 'github' | 'admin'
}

interface AuthContextType {
  user: User | null
  login: (user: User, token: string) => void
  logout: () => Promise<void>
  isLoading: boolean
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // 로컬 스토리지에서 사용자 정보와 토큰 복원
    const loadUserFromStorage = async () => {
      const savedUser = localStorage.getItem('user')
      const savedToken = localStorage.getItem('auth_token')
      
      if (savedUser && savedToken) {
        try {
          // 토큰 유효성 검사
          await apiClient.verifyToken()
          setUser(JSON.parse(savedUser))
        } catch (error) {
          console.error('Token verification failed:', error)
          localStorage.removeItem('user')
          localStorage.removeItem('auth_token')
          setUser(null)
        }
      }
      setIsLoading(false)
    }

    loadUserFromStorage()
  }, [])

  const login = (userData: User, token: string) => {
    setUser(userData)
    localStorage.setItem('user', JSON.stringify(userData))
    localStorage.setItem('auth_token', token)
  }

  const logout = async () => {
    try {
      await apiClient.logout()
    } finally {
      setUser(null)
      localStorage.removeItem('user')
      localStorage.removeItem('auth_token')
    }
  }

  return (
    <AuthContext.Provider value={{ user, login, logout, isLoading }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
