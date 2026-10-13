"use client"

import React from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Copy, DollarSign, TrendingUp, AlertTriangle } from "lucide-react"
import { CostAnalysisResponse } from "@/lib/types/nlp-response"
import { copyToClipboard } from "@/lib/utils/clipboard"

interface CostAnalysisRendererProps {
  response: CostAnalysisResponse
}

export function CostAnalysisRenderer({ response }: CostAnalysisRendererProps) {
  const { data } = response
  const formatted = data.formatted || {}
  const monthly_cost = formatted.current_cost || 0
  const currency = "₩"
  const optimization_suggestions = formatted.optimizations?.length || 0
  const cost_breakdown = {
    compute: monthly_cost * 0.6, // 예시 비율
    storage: monthly_cost * 0.2,
    network: monthly_cost * 0.2
  }


  const formatCurrency = (amount: number, currency: string) => {
    if (currency === 'KRW' || currency === '₩') {
      return `₩${amount.toLocaleString()}`
    }
    return `${currency}${amount.toLocaleString()}`
  }

  const getCostStatus = (cost: number) => {
    if (cost === 0) {
      return <Badge variant="default" className="bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200">무료</Badge>
    } else if (cost < 100) {
      return <Badge variant="secondary" className="bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200">저비용</Badge>
    } else if (cost < 1000) {
      return <Badge variant="outline" className="bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200">중간비용</Badge>
    } else {
      return <Badge variant="destructive">고비용</Badge>
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <DollarSign className="w-5 h-5" />
              비용 분석
            </CardTitle>
            <CardDescription>
              {response.message}
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
      </CardHeader>
      
      <CardContent className="space-y-6">
        {/* 총 비용 */}
        <div className="bg-muted/50 p-4 rounded-lg">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-lg font-semibold">월 예상 비용</h3>
            {getCostStatus(monthly_cost)}
          </div>
          <div className="text-3xl font-bold text-primary">
            {formatCurrency(monthly_cost, currency)}
          </div>
        </div>

        {/* 비용 세부 내역 */}
        <div>
          <h3 className="text-lg font-semibold mb-3">비용 세부 내역</h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="bg-blue-50 dark:bg-blue-950/20 p-4 rounded-lg border border-blue-200 dark:border-blue-800">
              <div className="flex items-center gap-2 mb-2">
                <TrendingUp className="w-4 h-4 text-blue-600" />
                <span className="font-medium">컴퓨팅</span>
              </div>
              <div className="text-xl font-bold text-blue-600">
                {formatCurrency(cost_breakdown.compute, currency)}
              </div>
            </div>
            
            <div className="bg-green-50 dark:bg-green-950/20 p-4 rounded-lg border border-green-200 dark:border-green-800">
              <div className="flex items-center gap-2 mb-2">
                <TrendingUp className="w-4 h-4 text-green-600" />
                <span className="font-medium">스토리지</span>
              </div>
              <div className="text-xl font-bold text-green-600">
                {formatCurrency(cost_breakdown.storage, currency)}
              </div>
            </div>
            
            <div className="bg-purple-50 dark:bg-purple-950/20 p-4 rounded-lg border border-purple-200 dark:border-purple-800">
              <div className="flex items-center gap-2 mb-2">
                <TrendingUp className="w-4 h-4 text-purple-600" />
                <span className="font-medium">네트워크</span>
              </div>
              <div className="text-xl font-bold text-purple-600">
                {formatCurrency(cost_breakdown.network, currency)}
              </div>
            </div>
          </div>
        </div>

        {/* 최적화 제안 */}
        {optimization_suggestions > 0 && (
          <div className="bg-yellow-50 dark:bg-yellow-950/20 p-4 rounded-lg border border-yellow-200 dark:border-yellow-800">
            <div className="flex items-center gap-2 mb-2">
              <AlertTriangle className="w-4 h-4 text-yellow-600" />
              <span className="font-medium text-yellow-800 dark:text-yellow-200">최적화 제안</span>
            </div>
            <p className="text-sm text-yellow-700 dark:text-yellow-300">
              {optimization_suggestions}개의 비용 최적화 제안이 있습니다.
            </p>
          </div>
        )}

        {optimization_suggestions === 0 && monthly_cost > 0 && (
          <div className="bg-green-50 dark:bg-green-950/20 p-4 rounded-lg border border-green-200 dark:border-green-800">
            <div className="flex items-center gap-2 mb-2">
              <TrendingUp className="w-4 h-4 text-green-600" />
              <span className="font-medium text-green-800 dark:text-green-200">최적화 완료</span>
            </div>
            <p className="text-sm text-green-700 dark:text-green-300">
              현재 비용이 최적화되어 있습니다.
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
