"use client"

import { useEffect, useRef, useState } from "react"
import { config } from "@/lib/config"

interface WebSocketMessage {
  type: string
  deployment_id?: string
  user_id?: string
  stage?: string
  status?: string
  progress?: number
  elapsed_time?: number
  message?: string
  connected?: boolean
  data?: any
  timestamp?: string
}

interface WebSocketSubscriber {
  id: string
  deployment_id?: string
  user_id?: string
  onMessage: (message: WebSocketMessage) => void
  onError?: (error: Event) => void
  onClose?: (event: CloseEvent) => void
}

class GlobalWebSocketManager {
  private static instance: GlobalWebSocketManager
  private ws: WebSocket | null = null
  private subscribers: Map<string, WebSocketSubscriber> = new Map()
  private reconnectTimeout: NodeJS.Timeout | null = null
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private _isConnecting = false
  // 서버-클라이언트 시간 오프셋(ms): server_timestamp - client_now
  private serverTimeOffsetMs = 0

  static getInstance(): GlobalWebSocketManager {
    if (!GlobalWebSocketManager.instance) {
      GlobalWebSocketManager.instance = new GlobalWebSocketManager()
    }
    return GlobalWebSocketManager.instance
  }

  get isConnecting(): boolean {
    return this._isConnecting
  }

  connect() {
    if (this.ws?.readyState === WebSocket.OPEN || this._isConnecting) {
      return
    }

    // 기존 연결이 있다면 정리
    if (this.ws) {
      this.ws.onopen = null
      this.ws.onmessage = null
      this.ws.onerror = null
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }

    this._isConnecting = true
    const token = typeof window !== 'undefined' ? localStorage.getItem('auth_token') : null
    const wsUrl = token
      ? `${config.api.wsUrl}/api/v1/ws/deployments?token=${encodeURIComponent(token)}`
      : `${config.api.wsUrl}/api/v1/ws/deployments`

    try {
      this.ws = new WebSocket(wsUrl)
      
      this.ws.onopen = () => {
        // connected
        this._isConnecting = false
        this.reconnectAttempts = 0
        
        // 연결 후 즉시 ping 메시지 전송 및 heartbeat 시작
        this.sendMessage({ type: "ping" })
        this.startHeartbeat()
        
        // 모든 구독자에게 연결 상태 알림
        this.subscribers.forEach(subscriber => {
          if (subscriber.onMessage) {
            subscriber.onMessage({
              type: "connection_status",
              connected: true
            })
          }
        })
        
        // WebSocket 연결 완료 후 모든 구독자들에게 subscribe 메시지 전송
        this.subscribers.forEach((subscriber) => {
            if (subscriber.user_id) {
            this.sendMessage({
              type: "subscribe",
              subscriber_id: subscriber.id,
              deployment_id: subscriber.deployment_id,
              user_id: subscriber.user_id
            })
          } else { /* no-op */ }
        })
      }

      this.ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data)
          // message received

          // 서버 시간 오프셋 보정 (가능한 가장 이른 지점에서 1회 이상 갱신)
          if (message.timestamp) {
            try {
              const serverNow = new Date(message.timestamp).getTime()
              const clientNow = Date.now()
              this.serverTimeOffsetMs = serverNow - clientNow
            } catch {}
          }
          
          // 모든 구독자에게 메시지 전파
          this.subscribers.forEach((subscriber) => {
            try {
              subscriber.onMessage(message)
            } catch (error) {
              console.error("Error in subscriber message handler:", error)
            }
          })
        } catch (error) { /* ignore parse error */ }
      }

      this.ws.onerror = (error) => {
        // error handler
        this._isConnecting = false
        
        // 모든 구독자에게 오류 전파
        this.subscribers.forEach((subscriber) => {
          if (subscriber.onError) {
            try {
              subscriber.onError(error)
              } catch (err) { /* ignore */ }
          }
        })
      }

      this.ws.onclose = (event) => {
        // closed
        this._isConnecting = false
        
        // 모든 구독자에게 종료 이벤트 전파
        this.subscribers.forEach((subscriber) => {
          if (subscriber.onClose) {
            try {
              subscriber.onClose(event)
            } catch (error) {
              console.error("Error in subscriber close handler:", error)
            }
          }
        })

        // 재연결 시도 (정상 종료가 아닌 경우에만)
        if (event.code !== 1000 && event.code !== 1001 && this.reconnectAttempts < this.maxReconnectAttempts) {
          // 빠른 재연결: 500ms부터 시작, 상한 5000ms
          const delay = Math.min(500 * Math.pow(1.4, this.reconnectAttempts), 5000)
          // reconnecting soon
          
          this.reconnectTimeout = setTimeout(() => {
            this.reconnectAttempts++
            this.connect()
          }, delay)
        } else if (event.code === 1000 || event.code === 1001) { /* normal close */ } else { /* reached max */ }
      }
    } catch (error) {
      // connection create failed
      this._isConnecting = false
    }
  }

  // 주기적 ping 송신(연결 유지를 위해)
  private startHeartbeat() {
    // 25초마다 ping
    const ws = this.ws
    if (!ws) return
    const interval = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify({ type: "ping" })) } catch {}
      } else {
        clearInterval(interval)
      }
    }, 25000)
  }

  subscribe(subscriber: WebSocketSubscriber) {
    // 이미 같은 ID의 구독자가 있다면 제거
    if (this.subscribers.has(subscriber.id)) {
      this.subscribers.delete(subscriber.id)
    }
    
    this.subscribers.set(subscriber.id, subscriber)
    // subscriber added
    
    // 첫 번째 구독자일 때 연결 시작
    if (this.subscribers.size === 1) {
      this.connect()
    }
    
    // WebSocket이 이미 연결된 상태에서 새로운 구독자가 추가된 경우 즉시 subscribe 메시지 전송
    if (this.ws?.readyState === WebSocket.OPEN && subscriber.user_id) {
      this.sendMessage({
        type: "subscribe",
        subscriber_id: subscriber.id,
        deployment_id: subscriber.deployment_id,
        user_id: subscriber.user_id
      })
    } else if (this.ws?.readyState === WebSocket.OPEN && !subscriber.user_id) { /* no-op */ }
  }

  unsubscribe(id: string) {
    if (this.subscribers.has(id)) {
      this.subscribers.delete(id)
      
      // 마지막 구독자일 때 연결 종료 (지연)
      if (this.subscribers.size === 0) {
        // 잠시 대기 후 연결 종료 (새로운 구독자가 올 수 있음)
        setTimeout(() => {
          if (this.subscribers.size === 0) {
            this.disconnect()
          }
        }, 1000)
      }
    }
  }

  private disconnect() {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout)
      this.reconnectTimeout = null
    }

    if (this.ws) {
      // 연결이 열려있을 때만 닫기
      if (this.ws.readyState === WebSocket.OPEN) {
        this.ws.close(1000, "No more subscribers")
      }
      this.ws = null
    }
  }

  sendMessage(message: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    } else {
      console.warn("Global WebSocket is not connected. Cannot send message.")
    }
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  getServerTimeOffsetMs(): number {
    return this.serverTimeOffsetMs
  }
}

export function useGlobalWebSocket({
  deploymentId,
  userId,
  onMessage,
  onError,
  onClose
}: {
  deploymentId?: number
  userId?: string
  onMessage?: (message: WebSocketMessage) => void
  onError?: (error: Event) => void
  onClose?: (event: CloseEvent) => void
}) {
  const [isConnected, setIsConnected] = useState(false)
  const [connectionStatus, setConnectionStatus] = useState<"connecting" | "connected" | "disconnected" | "error">("disconnected")
  const subscriberId = useRef<string>(`${deploymentId || userId || 'global'}-${Math.random().toString(36).substr(2, 9)}`)
  
  // 구독자 ID가 변경되지 않도록 보장
  if (!subscriberId.current) {
    subscriberId.current = `${deploymentId || userId || 'global'}-${Math.random().toString(36).substr(2, 9)}`
  }

  useEffect(() => {
    const manager = GlobalWebSocketManager.getInstance()
    // userId received
    
    const subscriber: WebSocketSubscriber = {
      id: subscriberId.current,
      deployment_id: deploymentId ? String(deploymentId) : undefined,
      user_id: userId,
      onMessage: (message) => {
        if (onMessage) {
          onMessage(message)
        }
      },
      onError: (error) => {
        setConnectionStatus("error")
        if (onError) {
          onError(error)
        }
      },
      onClose: (event) => {
        setConnectionStatus("disconnected")
        setIsConnected(false)
        if (onClose) {
          onClose(event)
        }
      }
    }

    // 연결 상태 업데이트
    const updateConnectionStatus = () => {
      const connected = manager.isConnected()
      setIsConnected(connected)
      setConnectionStatus(connected ? "connected" : "disconnected")
      
      // 연결이 끊어졌을 때 즉시 재연결 시도
      if (!connected && !manager.isConnecting) {
        // reconnect on disconnect
        manager.connect()
      }
      
      // 연결 상태 로깅
      // (reduced noisy logs)
    }

    // 초기 상태 설정
    updateConnectionStatus()

    // 구독
    manager.subscribe(subscriber)

        // 주기적으로 연결 상태 확인 (더 짧은 간격으로)
        const statusInterval = setInterval(updateConnectionStatus, 2000)

    return () => {
      clearInterval(statusInterval)
      // 즉시 구독 해제
      manager.unsubscribe(subscriberId.current)
    }
  }, [userId, deploymentId])

  // userId가 변경될 때 기존 연결을 유지하면서 subscribe 메시지만 재전송
  useEffect(() => {
    if (userId && isConnected) {
      const manager = GlobalWebSocketManager.getInstance()
      // userId changed; re-subscribe
      
      // 기존 구독자를 업데이트하고 subscribe 메시지 재전송
      manager.subscribe({
        id: subscriberId.current,
        deployment_id: deploymentId ? String(deploymentId) : undefined,
        user_id: userId,
        onMessage: (message) => {
          if (onMessage) {
            onMessage(message)
          }
        },
        onError: (error) => {
          setConnectionStatus("error")
          if (onError) {
            onError(error)
          }
        },
        onClose: (event) => {
          setConnectionStatus("disconnected")
          setIsConnected(false)
          if (onClose) {
            onClose(event)
          }
        }
      })
    }
  }, [userId]) // userId와 deploymentId가 변경되면 재실행

  const sendMessage = (message: any) => {
    const manager = GlobalWebSocketManager.getInstance()
    manager.sendMessage(message)
  }

  return {
    isConnected,
    connectionStatus,
    sendMessage
  }
}
