"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { 
  RotateCcw, 
  CheckCircle, 
  AlertCircle,
  GitCommit,
  ArrowLeft
} from "lucide-react"
import { RollbackResponse, RollbackData } from "@/lib/types/nlp-response"
import { cn } from "@/lib/utils"

interface RollbackRendererProps {
  response: RollbackResponse
}

export function RollbackRenderer({ response }: RollbackRendererProps) {
  const data = response.data?.formatted as RollbackData

  const formatCommitHash = (commit: string) => {
    return commit.length > 7 ? commit.substring(0, 7) : commit
  }

  return (
    <Card className="w-full max-w-2xl mx-auto">
      <CardHeader className="pb-3">
        <div className="flex items-center gap-2">
          <div className="p-2 bg-green-100 dark:bg-green-900/20 rounded-lg">
            <CheckCircle className="w-5 h-5 text-green-600 dark:text-green-400" />
          </div>
          <div>
            <CardTitle className="text-lg">롤백 완료</CardTitle>
            <CardDescription>
              {data.owner}/{data.repo} 저장소가 성공적으로 롤백되었습니다
            </CardDescription>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-3">
        {/* 롤백 정보 */}
        <div className="bg-muted/50 p-3 rounded-lg">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <GitCommit className="w-4 h-4 text-blue-600" />
              <span className="text-sm font-medium text-muted-foreground">대상 커밋:</span>
            </div>
            <code className="text-sm font-mono bg-background px-3 py-1.5 rounded border">
              {formatCommitHash(data.target_commit)}
            </code>
          </div>
        </div>

        {/* 이전 커밋 정보 */}
        {data.previous_commit && (
          <>
            <Separator />
            <div className="bg-amber-50 dark:bg-amber-900/20 p-3 rounded-lg">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <ArrowLeft className="w-4 h-4 text-amber-600" />
                  <span className="text-sm font-medium text-muted-foreground">롤백 전 버전:</span>
                </div>
                <code className="text-sm font-mono bg-background px-3 py-1.5 rounded border">
                  {formatCommitHash(data.previous_commit)}
                </code>
              </div>
            </div>
          </>
        )}

        {/* 상태 표시 */}
        <div className="flex items-center justify-center pt-2">
          <Badge 
            variant="default" 
            className={cn(
              "px-4 py-2 text-sm font-medium",
              "bg-green-100 text-green-800 dark:bg-green-900/20 dark:text-green-400"
            )}
          >
            <CheckCircle className="w-4 h-4 mr-2" />
            롤백이 성공적으로 완료되었습니다
          </Badge>
        </div>
      </CardContent>
    </Card>
  )
}
