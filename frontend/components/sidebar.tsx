"use client"

import { useEffect, useState } from "react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  LayoutDashboard,
  Terminal,
  GitBranch,
  Activity,
  Settings,
  ChevronLeft,
  ChevronRight,
  Github,
} from "lucide-react"

const navigation = [
  { name: "Dashboard", href: "dashboard", icon: LayoutDashboard },
  { name: "Commands", href: "commands", icon: Terminal },
  { name: "Deployments", href: "deployments", icon: GitBranch },
  { name: "GitHub", href: "github", icon: Github },
  { name: "Monitoring", href: "monitoring", icon: Activity, preview: true },
  { name: "Settings", href: "settings", icon: Settings },
]

interface SidebarProps {
  activeView: string
  setActiveView: (view: string) => void
}

export function Sidebar({ activeView, setActiveView }: SidebarProps) {
  const [collapsed, setCollapsed] = useState(false)

  // 좁은 화면(768px 미만)에서는 접힌 막대로 시작하고, 그 폭으로 줄면 접는다. 펼치는 것은 사용자가 고른다 (#109)
  useEffect(() => {
    const narrow = window.matchMedia("(max-width: 767px)")
    if (narrow.matches) setCollapsed(true)
    const onChange = (event: MediaQueryListEvent) => {
      if (event.matches) setCollapsed(true)
    }
    narrow.addEventListener("change", onChange)
    return () => narrow.removeEventListener("change", onChange)
  }, [])

  return (
    <div
      className={cn(
        "bg-sidebar border-r border-sidebar-border transition-all duration-300",
        collapsed ? "w-16" : "w-64",
      )}
    >
      <div className="flex h-full flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-4 h-16 border-b border-border">
          <div className="flex items-center justify-start flex-1 pl-1">
            <img 
              src="https://k-paas.or.kr/resources/img/logo_main.png" 
              alt="K-PaaS Logo" 
              className={cn(
                "object-contain",
                collapsed ? "w-8 h-8" : "w-28 h-8"
              )}
            />
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setCollapsed(!collapsed)}
            className="text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
          >
            {collapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
          </Button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 p-4 space-y-2">
          {navigation.map((item) => {
            const Icon = item.icon
            const isActive = activeView === item.href
            return (
              <Button
                key={item.name}
                variant={isActive ? "default" : "ghost"}
                className={cn(
                  "w-full justify-start",
                  isActive
                    ? "bg-sidebar-accent text-sidebar-accent-foreground"
                    : "text-sidebar-foreground hover:bg-sidebar-primary hover:text-sidebar-primary-foreground",
                  collapsed && "px-2",
                )}
                onClick={() => {
                  if (item.href === "monitoring") {
                    try {
                      const url = new URL(window.location.href)
                      // 기본 탭을 nodes로 강제 설정하여 이전에 남은 tab 파라미터를 초기화
                      url.searchParams.set('tab', 'nodes')
                      window.history.replaceState({}, '', url.toString())
                      // 현재 모니터링 화면에 있을 수도 있으므로 이벤트로 탭 전환 신호도 보냄
                      const evt = new CustomEvent('setMonitoringTab', { detail: { tab: 'nodes' } })
                      window.dispatchEvent(evt)
                    } catch {}
                  }
                  setActiveView(item.href)
                }}
              >
                <Icon className={cn("w-4 h-4", !collapsed && "mr-3")} />
                {!collapsed && item.name}
                {!collapsed && "preview" in item && item.preview && (
                  <span className="ml-auto text-[10px] text-sidebar-foreground/60">준비 중</span>
                )}
              </Button>
            )
          })}
        </nav>

        {/* Footer */}
        <div className="p-4 border-t border-sidebar-border">
          {!collapsed && <div className="text-xs text-sidebar-foreground/60">Agent-safe Kubernetes PaaS</div>}
        </div>
      </div>
    </div>
  )
}