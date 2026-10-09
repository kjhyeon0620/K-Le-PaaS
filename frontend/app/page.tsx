"use client"

import { useEffect, useState } from "react"
import { useToast } from "@/hooks/use-toast"
import { AuthProvider } from "@/contexts/auth-context"
import { Sidebar } from "@/components/sidebar"
import { Header } from "@/components/header"
import { DashboardOverview } from "@/components/dashboard-overview"
import { NaturalLanguageCommand } from "@/components/natural-language-command"
import { DeploymentStatusMonitoring } from "@/components/deployment-status-monitoring"
import { GitHubIntegrationPanel } from "@/components/github-integration-panel"
import { RealTimeMonitoringDashboard } from "@/components/real-time-monitoring-dashboard"
import { SettingsPage } from "@/components/settings/SettingsPage"

export default function HomePage() {
  const [activeView, setActiveView] = useState("dashboard")
  const [githubInitialTab, setGithubInitialTab] = useState("repositories")
  const [configureRepo, setConfigureRepo] = useState<string | null>(null)
  const [monitoringInitialTab, setMonitoringInitialTab] = useState<'nodes' | 'details' | 'alerts' | 'resources'>('nodes')
  const [scrollToMessageId, setScrollToMessageId] = useState<number | undefined>(undefined)
  const { toast } = useToast()

  const VIEW_KEY = "kpaas_active_view"
  const VALID_VIEWS = ["dashboard", "commands", "deployments", "github", "monitoring", "settings"] as const

  // Restore active view from URL (?view=) or localStorage
  useEffect(() => {
    try {
      const search = typeof window !== "undefined" ? new URLSearchParams(window.location.search) : undefined
      const fromUrl = search?.get("view") || undefined
      const fromStorage = typeof window !== "undefined" ? window.localStorage.getItem(VIEW_KEY) || undefined : undefined
      const candidate = fromUrl || fromStorage
      if (candidate && (VALID_VIEWS as readonly string[]).includes(candidate)) {
        setActiveView(candidate)
      }
    } catch {}
  }, [])

  // Persist active view to URL and localStorage
  useEffect(() => {
    try {
      if (typeof window !== "undefined") {
        window.localStorage.setItem(VIEW_KEY, activeView)
        const url = new URL(window.location.href)
        url.searchParams.set("view", activeView)
        window.history.replaceState({}, "", url.toString())
      }
    } catch {}
  }, [activeView])

  // Show Slack connected toast when redirected with ?slack=connected
  useEffect(() => {
    try {
      const search = typeof window !== "undefined" ? new URLSearchParams(window.location.search) : undefined
      if (search?.get("slack") === "connected") {
        try {
          toast({ title: "Slack 연동 완료", description: "이제 배포 알림을 Slack에서 받아요.", duration: 3000 })
        } catch {
          // fallback
          // eslint-disable-next-line no-alert
          alert("Slack 연동이 완료되었습니다.")
        }
        const url = new URL(window.location.href)
        url.searchParams.delete("slack")
        window.history.replaceState({}, "", url.toString())
      }
    } catch {}
  }, [])

  // 대시보드에서 대화창으로 이동하는 함수
  const handleNavigateToChat = (commandId: number) => {
    setScrollToMessageId(commandId)
    setActiveView("commands")
  }

  // 스크롤 완료 후 scrollToMessageId 초기화
  const handleScrollComplete = () => {
    setScrollToMessageId(undefined)
  }

  const renderContent = () => {
    switch (activeView) {
      case "commands":
        return <NaturalLanguageCommand 
          scrollToMessageId={scrollToMessageId}
          onScrollComplete={handleScrollComplete}
          onNavigateToPipelines={() => {
            setGithubInitialTab("pipelines")
            setActiveView("github")
          }} 
        />
      case "deployments":
        return <DeploymentStatusMonitoring 
          configureRepo={configureRepo}
          onConfigureRepoHandled={() => setConfigureRepo(null)}
          onNavigateToMonitoring={() => setActiveView("monitoring")}
          onNavigateToPipelines={() => {
            setGithubInitialTab("pipelines")
            setActiveView("github")
          }}
        />
      case "github":
        return <GitHubIntegrationPanel 
          onNavigateToPipelines={() => setActiveView("github")} 
          initialTab={githubInitialTab}
          onConfigureRepository={(fullName) => {
            setConfigureRepo(fullName)
            setActiveView("deployments")
          }}
        />
      case "monitoring":
        return <RealTimeMonitoringDashboard initialTab={monitoringInitialTab} />
      case "settings":
        return <SettingsPage />
      case "dashboard":
      default:
        return <DashboardOverview 
          onNavigateToDeployments={() => setActiveView("deployments")}
          onNavigateToChat={handleNavigateToChat}
          onNavigateToRepositories={() => {
            setGithubInitialTab("repositories")
            setActiveView("github")
          }}
          onNavigateToPullRequests={() => {
            setGithubInitialTab("pullrequests")
            setActiveView("github")
          }}
          onNavigateToMonitoring={(tab = 'nodes') => {
            setMonitoringInitialTab(tab)
            setActiveView("monitoring")
          }}
        />
    }
  }

  return (
    <AuthProvider>
      <div className="flex h-screen bg-background">
        <Sidebar activeView={activeView} setActiveView={setActiveView} />
        <div className="flex-1 flex flex-col overflow-hidden">
          <Header />
          <main className="flex-1 overflow-y-auto p-6">{renderContent()}</main>
        </div>
      </div>
    </AuthProvider>
  )
}
