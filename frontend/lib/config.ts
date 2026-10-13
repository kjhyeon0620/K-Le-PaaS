/**
 * 애플리케이션 설정
 * 환경변수를 한 곳에서 관리합니다.
 *
 * ⚠️ 주의: 필수 환경변수는 빌드 시점에 반드시 설정되어야 합니다.
 * 기본값을 제공하지 않으므로 누락 시 에러가 발생합니다.
 */

export const config = {
  // API 기본 URL
  api: {
    baseUrl: process.env.NEXT_PUBLIC_API_URL || 'https://klepaas.com',
    wsUrl: process.env.NEXT_PUBLIC_WS_URL || 'wss://klepaas.com',
  },

  // 환경 정보
  env: {
    isDevelopment: process.env.NODE_ENV === 'development',
    isProduction: process.env.NODE_ENV === 'production',
    nodeEnv: process.env.NODE_ENV || 'production',
  },

  // 기타 설정 (선택적)
  app: {
    basePath: process.env.NEXT_PUBLIC_BASE_PATH || '',
  },
} as const

// 타입 안전성을 위한 헬퍼
export const getApiUrl = (endpoint: string) => {
  return `${config.api.baseUrl}${endpoint}`
}

export const getWsUrl = (endpoint: string) => {
  return `${config.api.wsUrl}${endpoint}`
}
