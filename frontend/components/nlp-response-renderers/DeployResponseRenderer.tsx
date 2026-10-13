"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { ExternalLink, GitBranch, User, Calendar, MessageSquare, ArrowRight } from "lucide-react"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { cn } from "@/lib/utils"

interface DeployResponseRendererProps {
  response: {
    data: {
      formatted: {
        status?: string
        message?: string
        repository?: string
        branch?: string
        commit?: {
          sha: string
          message: string
          author: string
          url?: string
        }
        deployment_status?: string
        app_name?: string
        environment?: string
        [key: string]: any
      }
    }
  }
  onNavigateToPipelines?: () => void
}

export function DeployResponseRenderer({ response, onNavigateToPipelines }: DeployResponseRendererProps) {
  const { data } = response
  const { 
    message = "배포가 시작되었습니다", 
    repository = "알 수 없는 저장소", 
    branch = "main", 
    commit = { sha: "unknown", message: "커밋 정보 없음", author: "Unknown" }, 
    deployment_status = "진행 중" 
  } = data.formatted


  const formatCommitSha = (sha: string) => {
    return sha.substring(0, 7)
  }

  return (
    <Card className="w-full border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
            <CardTitle className="text-lg text-green-800 dark:text-green-200">
              배포 시작됨
            </CardTitle>
          </div>
          <Badge variant="secondary" className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">
            진행 중
          </Badge>
        </div>
        <CardDescription className="text-green-700 dark:text-green-300">
          {message}
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* 저장소 정보 */}
        <div className="flex items-center justify-between p-3 bg-white dark:bg-gray-800 rounded-lg border">
          <div className="flex items-center gap-2">
            <GitBranch className="w-4 h-4 text-gray-500" />
            <span className="font-medium">{repository}</span>
            <Badge variant="outline" className="text-xs">
              {branch}
            </Badge>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => copyToClipboard(repository)}
            className="h-6 w-6 p-0"
          >
            <ExternalLink className="w-3 h-3" />
          </Button>
        </div>

        {/* 커밋 정보 */}
        <div className="p-3 bg-white dark:bg-gray-800 rounded-lg border">
          <div className="flex items-start justify-between mb-2">
            <div className="flex items-center gap-2">
              <MessageSquare className="w-4 h-4 text-gray-500" />
              <span className="text-sm font-medium">최신 커밋</span>
            </div>
            <div className="flex items-center gap-2">
              <code className="text-xs bg-gray-100 dark:bg-gray-700 px-2 py-1 rounded">
                {formatCommitSha(commit.sha)}
              </code>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => copyToClipboard(commit.sha)}
                className="h-6 w-6 p-0"
              >
                <ExternalLink className="w-3 h-3" />
              </Button>
            </div>
          </div>
          <p className="text-sm text-gray-700 dark:text-gray-300 mb-2">
            {commit.message}
          </p>
          <div className="flex items-center gap-4 text-xs text-gray-500">
            <div className="flex items-center gap-1">
              <User className="w-3 h-3" />
              <span>{commit.author}</span>
            </div>
            <div className="flex items-center gap-1">
              <Calendar className="w-3 h-3" />
              <span>방금 전</span>
            </div>
          </div>
        </div>

        {/* 배포 상태 및 안내 */}
        <div className="p-4 bg-blue-50 dark:bg-blue-950 rounded-lg border border-blue-200 dark:border-blue-800">
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="text-sm text-blue-800 dark:text-blue-200 mb-2">
                {deployment_status}
              </p>
              <p className="text-xs text-blue-600 dark:text-blue-400">
                배포 진행 상황을 실시간으로 확인할 수 있습니다.
              </p>
            </div>
            {onNavigateToPipelines && (
              <Button
                onClick={onNavigateToPipelines}
                size="sm"
                className="ml-3 bg-blue-600 hover:bg-blue-700 text-white"
              >
                <ArrowRight className="w-4 h-4 mr-1" />
                CI/CD Pipelines
              </Button>
            )}
          </div>
        </div>

        {/* 추가 정보 */}
        <div className="text-xs text-gray-500 space-y-1">
          <p>• 배포는 백그라운드에서 진행됩니다</p>
          <p>• 완료 시 알림을 받을 수 있습니다</p>
          <p>• 언제든지 롤백이 가능합니다</p>
        </div>
      </CardContent>
    </Card>
  )
}
