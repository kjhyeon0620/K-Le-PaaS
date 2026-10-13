"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { 
  AlertTriangle, 
  Copy, 
  RefreshCw, 
  HelpCircle,
  CheckCircle,
  XCircle,
  Info,
  Lightbulb,
  Command
} from "lucide-react"
import { CommandErrorResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"
import { cn } from "@/lib/utils"

interface CommandErrorRendererProps {
  response: CommandErrorResponse
}

export function CommandErrorRenderer({ response }: CommandErrorRendererProps) {
  const { data, metadata } = response
  const errorData = data.formatted || {}
  

  const getErrorIcon = (errorType: string) => {
    switch (errorType.toLowerCase()) {
      case 'validation':
      case 'missing_info':
        return <HelpCircle className="w-5 h-5 text-yellow-500" />
      case 'not_found':
        return <XCircle className="w-5 h-5 text-red-500" />
      case 'permission':
        return <AlertTriangle className="w-5 h-5 text-orange-500" />
      case 'system':
        return <AlertTriangle className="w-5 h-5 text-red-500" />
      default:
        return <AlertTriangle className="w-5 h-5 text-red-500" />
    }
  }

  const getErrorSeverity = (errorType: string) => {
    switch (errorType.toLowerCase()) {
      case 'validation':
      case 'missing_info':
        return { color: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200", label: "정보 부족" }
      case 'not_found':
        return { color: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200", label: "리소스 없음" }
      case 'permission':
        return { color: "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200", label: "권한 없음" }
      case 'system':
        return { color: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200", label: "시스템 오류" }
      default:
        return { color: "bg-gray-100 text-gray-800 dark:bg-gray-900 dark:text-gray-200", label: "알 수 없음" }
    }
  }

  const errorType = errorData.error_type || "system"
  const severity = getErrorSeverity(errorType)
  const icon = getErrorIcon(errorType)

  return (
    <Card className="w-full border-red-200 dark:border-red-800">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {icon}
            <div>
              <CardTitle className="text-red-800 dark:text-red-200">
                명령어 오류
              </CardTitle>
              <CardDescription className="text-red-600 dark:text-red-400">
                {response.summary}
              </CardDescription>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="outline" className={severity.color}>
              {severity.label}
            </Badge>
            <Button
              variant="outline"
              size="sm"
              onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
            >
              <Copy className="w-4 h-4 mr-2" />
              복사
            </Button>
          </div>
        </div>
      </CardHeader>
      
      <CardContent>
        {/* 오류 요약 */}
        <div className="mb-4 p-3 bg-red-50 dark:bg-red-950/20 rounded-lg">
          <div className="flex items-center gap-2 mb-2">
            <AlertTriangle className="w-4 h-4 text-red-500" />
            <span className="font-medium text-red-800 dark:text-red-200">오류 내용</span>
          </div>
          <p className="text-sm text-red-700 dark:text-red-300">
            {errorData.error_message || "알 수 없는 오류가 발생했습니다."}
          </p>
        </div>

        <Separator className="my-4" />

        {/* 해결 방법 */}
        {errorData.solutions && errorData.solutions.length > 0 && (
          <div className="mb-4">
            <div className="flex items-center gap-2 mb-3">
              <Lightbulb className="w-4 h-4 text-green-500" />
              <span className="font-medium">해결 방법</span>
            </div>
            
            <div className="space-y-2">
              {errorData.solutions.map((solution: any, index: number) => (
                <div key={index} className="flex items-start gap-2 p-2 bg-green-50 dark:bg-green-950/20 rounded">
                  <div className="w-5 h-5 rounded-full bg-green-100 dark:bg-green-900 flex items-center justify-center mt-0.5">
                    <span className="text-xs font-medium text-green-800 dark:text-green-200">
                      {index + 1}
                    </span>
                  </div>
                  <div className="flex-1">
                    <p className="text-sm text-green-800 dark:text-green-200 font-medium">
                      {solution.title}
                    </p>
                    {solution.description && (
                      <p className="text-xs text-green-600 dark:text-green-400 mt-1">
                        {solution.description}
                      </p>
                    )}
                    {solution.example && (
                      <code className="text-xs bg-green-100 dark:bg-green-900 px-2 py-1 rounded mt-1 inline-block">
                        {solution.example}
                      </code>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 지원되는 명령어 */}
        {errorData.supported_commands && errorData.supported_commands.length > 0 && (
          <div className="mb-4">
            <div className="flex items-center gap-2 mb-3">
              <Command className="w-4 h-4 text-blue-500" />
              <span className="font-medium">지원되는 명령어</span>
            </div>
            
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
              {errorData.supported_commands.map((command: any, index: number) => (
                <div key={index} className="p-2 bg-blue-50 dark:bg-blue-950/20 rounded border">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant="outline" className="text-xs">
                      {command.category}
                    </Badge>
                  </div>
                  <p className="text-sm text-blue-800 dark:text-blue-200 font-medium">
                    {command.name}
                  </p>
                  {command.example && (
                    <code className="text-xs text-blue-600 dark:text-blue-400 mt-1 block">
                      {command.example}
                    </code>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 상세 오류 정보 */}
        {errorData.technical_details && (
          <div>
            <details className="group">
              <summary className="flex items-center gap-2 cursor-pointer text-sm text-gray-600 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-200">
                <RefreshCw className="w-4 h-4 group-open:rotate-180 transition-transform" />
                상세 오류 정보
              </summary>
              <div className="mt-2 p-3 bg-gray-50 dark:bg-gray-900/50 rounded border">
                <pre className="text-xs text-gray-700 dark:text-gray-300 overflow-x-auto">
                  {errorData.technical_details}
                </pre>
              </div>
            </details>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
