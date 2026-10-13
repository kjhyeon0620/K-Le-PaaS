"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, BookOpen, Lightbulb } from "lucide-react"
import { ListCommandsResponse, CommandCategory } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface ListCommandsRendererProps {
  response: ListCommandsResponse
}

export function ListCommandsRenderer({ response }: ListCommandsRendererProps) {
  const { data, metadata } = response

  // 안전하게 데이터 추출
  const formatted = data.formatted || {}
  const categories = formatted.categories || []
  const total_commands = formatted.total_commands || metadata?.total_commands || 0
  const help_text = formatted.help_text || []

  // 데이터가 없는 경우 처리
  if (!categories || categories.length === 0) {
    return (
      <Card className="w-full">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <BookOpen className="w-5 h-5" />
            명령어 목록
          </CardTitle>
          <CardDescription>명령어 목록을 불러오는 중입니다...</CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground">데이터를 불러올 수 없습니다.</p>
          <details className="mt-2">
            <summary className="text-xs cursor-pointer">디버그 정보</summary>
            <pre className="text-xs bg-muted p-2 rounded mt-1 overflow-x-auto">
              {JSON.stringify(response, null, 2)}
            </pre>
          </details>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <BookOpen className="w-5 h-5" />
              K-Le-PaaS 사용 가능한 명령어
            </CardTitle>
            <CardDescription>
              {response.summary}
            </CardDescription>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => copyToClipboard(JSON.stringify(response, null, 2))}
          >
            <Copy className="w-4 h-4 mr-2" />
            복사
          </Button>
        </div>

        <div className="flex items-center gap-2 mt-4">
          <Badge variant="default">총 {total_commands}개 명령어</Badge>
          <Badge variant="outline">{metadata.category_count}개 카테고리</Badge>
        </div>
      </CardHeader>

      <CardContent className="space-y-6">
        {categories.map((category: CommandCategory, index: number) => (
          <div key={`${category.category}-${index}`} className="space-y-3">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-2xl">{category.icon}</span>
              <h3 className="text-lg font-semibold">{category.category}</h3>
            </div>

            <div className="grid gap-3 pl-8">
              {category.commands.map((cmd, cmdIndex) => (
                <div
                  key={`${cmd.name}-${cmdIndex}`}
                  className="p-4 rounded-lg border bg-card hover:bg-accent/50 transition-colors"
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-semibold text-base">{cmd.name_ko}</span>
                        <code className="text-xs px-2 py-1 rounded bg-muted text-muted-foreground">
                          {cmd.name}
                        </code>
                      </div>
                      <p className="text-sm text-muted-foreground mb-2">
                        {cmd.desc}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-start gap-2 text-sm">
                    <Lightbulb className="w-4 h-4 mt-0.5 text-amber-500 flex-shrink-0" />
                    <div className="flex-1">
                      <span className="text-muted-foreground">예시: </span>
                      <span className="font-mono text-sm bg-muted px-2 py-0.5 rounded">
                        "{cmd.example}"
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}

        {/* 도움말 섹션 */}
        <div className="mt-6 p-4 rounded-lg bg-primary/5 border border-primary/20">
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xl">💬</span>
            <h4 className="font-semibold">도움말</h4>
          </div>
          <ul className="space-y-1 pl-6">
            {help_text.map((text, index) => (
              <li key={index} className="text-sm text-muted-foreground list-disc">
                {text}
              </li>
            ))}
          </ul>
        </div>
      </CardContent>
    </Card>
  )
}
