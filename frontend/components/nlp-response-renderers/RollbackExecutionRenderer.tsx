"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { 
  Copy, 
  CheckCircle,
  GitCommit,
  User,
  Clock
} from "lucide-react"
import { RollbackExecutionResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface RollbackExecutionRendererProps {
  response: RollbackExecutionResponse
}

export function RollbackExecutionRenderer({ response }: RollbackExecutionRendererProps) {
  const { data, metadata } = response
  const rollbackData = data?.formatted || {}
  


  return (
    <Card className="w-full border-green-200 dark:border-green-800 bg-green-50/30 dark:bg-green-950/10">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-full bg-green-100 dark:bg-green-900 flex items-center justify-center">
              <CheckCircle className="w-6 h-6 text-green-600" />
            </div>
            <div>
              <CardTitle className="text-lg text-green-800 dark:text-green-200">
                롤백 완료
              </CardTitle>
              <CardDescription className="text-green-600 dark:text-green-400">
                {response.summary || "롤백이 성공적으로 완료되었습니다."}
              </CardDescription>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Badge className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200 border-green-200">
              성공
            </Badge>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
              className="text-gray-500 hover:text-gray-700"
            >
              <Copy className="w-4 h-4" />
            </Button>
          </div>
        </div>
      </CardHeader>
      
      <CardContent className="pt-0">
        {/* 핵심 정보만 표시 */}
        <div className="space-y-3">
          {/* 프로젝트 정보 */}
          {rollbackData.project && (
            <div className="flex items-center gap-3 p-3 bg-white dark:bg-gray-800/50 rounded-lg border">
              <div className="w-8 h-8 rounded-full bg-blue-100 dark:bg-blue-900 flex items-center justify-center">
                <User className="w-4 h-4 text-blue-600" />
              </div>
              <div className="flex-1">
                <div className="text-sm font-medium text-gray-900 dark:text-gray-100">
                  프로젝트
                </div>
                <div className="text-sm text-gray-600 dark:text-gray-400">
                  {rollbackData.project}
                </div>
              </div>
            </div>
          )}

          {/* 커밋 정보 */}
          {rollbackData.target_commit && (
            <div className="flex items-center gap-3 p-3 bg-white dark:bg-gray-800/50 rounded-lg border">
              <div className="w-8 h-8 rounded-full bg-purple-100 dark:bg-purple-900 flex items-center justify-center">
                <GitCommit className="w-4 h-4 text-purple-600" />
              </div>
              <div className="flex-1">
                <div className="text-sm font-medium text-gray-900 dark:text-gray-100">
                  롤백 커밋
                </div>
                <div className="text-sm text-gray-600 dark:text-gray-400 font-mono">
                  {rollbackData.target_commit}
                </div>
              </div>
            </div>
          )}

          {/* 시간 정보 */}
          {rollbackData.timestamp && (
            <div className="flex items-center gap-3 p-3 bg-white dark:bg-gray-800/50 rounded-lg border">
              <div className="w-8 h-8 rounded-full bg-orange-100 dark:bg-orange-900 flex items-center justify-center">
                <Clock className="w-4 h-4 text-orange-600" />
              </div>
              <div className="flex-1">
                <div className="text-sm font-medium text-gray-900 dark:text-gray-100">
                  완료 시간
                </div>
                <div className="text-sm text-gray-600 dark:text-gray-400">
                  {rollbackData.timestamp}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* 성공 메시지 */}
        <div className="mt-4 p-4 bg-green-100 dark:bg-green-900/30 rounded-lg border border-green-200 dark:border-green-800">
          <div className="flex items-center gap-2">
            <CheckCircle className="w-5 h-5 text-green-600" />
            <span className="font-medium text-green-800 dark:text-green-200">
              롤백이 성공적으로 완료되었습니다
            </span>
          </div>
          <p className="text-sm text-green-700 dark:text-green-300 mt-1">
            애플리케이션이 이전 버전으로 성공적으로 복구되었습니다.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
