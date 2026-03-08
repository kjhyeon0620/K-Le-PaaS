import { config } from './config'

class ApiClient {
  private baseURL: string

  constructor(baseURL: string = config.api.baseUrl) {
    this.baseURL = baseURL
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseURL}${endpoint}`

    const reqConfig: RequestInit = {
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-cache',
        'Pragma': 'no-cache',
        ...options.headers,
      },
      cache: 'no-store',
      ...options,
    }

    // Add JWT auth token if available
    const token = localStorage.getItem('auth_token')
    if (token) {
      reqConfig.headers = {
        ...reqConfig.headers,
        'Authorization': `Bearer ${token}`,
      }
    }

    try {
      const response = await fetch(url, reqConfig)

      if (!response.ok) {
        const errorText = await response.text()
        console.error(`HTTP error! status: ${response.status}, response:`, errorText)
        throw new Error(`HTTP error! status: ${response.status} - ${errorText}`)
      }

      const json = await response.json()

      // Auto-unwrap Java backend's ApiResponse<T> wrapper: {status, data, message}
      if (json && typeof json === 'object' && 'status' in json && 'data' in json) {
        if (json.status === 'success') {
          return json.data as T
        }
        throw new Error(json.message || 'API error')
      }

      return json as T
    } catch (error) {
      console.error('API request failed:', error)
      console.error('Request URL:', url)
      throw error
    }
  }

  // ─── Auth ────────────────────────────────────────────────────────────────

  async loginWithOAuth2(provider: 'google' | 'github', code: string, redirectUri: string) {
    // Backend only accepts { code }; provider/redirect_uri are ignored
    return this.request('/api/v1/auth/oauth2/login', {
      method: 'POST',
      body: JSON.stringify({ code }),
    })
  }

  async login(credentials: { email: string; password: string }) {
    return this.request('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    })
  }

  async adminLogin(credentials: { username: string; password: string }) {
    return this.request('/api/v1/auth/oauth2/admin/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    })
  }

  async register(userData: { email: string; password: string; name: string }) {
    return this.request('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify(userData),
    })
  }

  async logout() {
    const token = localStorage.getItem('auth_token')
    if (token) {
      try {
        await this.request('/api/v1/auth/logout', {
          method: 'POST',
        })
      } catch (error) {
        // 서버 로그아웃 실패해도 클라이언트 정리는 진행
        console.warn('Server logout failed:', error)
      }
    }
    return {}
  }

  async getCurrentUser() {
    return this.request('/api/v1/auth/me')
  }

  // OAuth2 URL generation — backend returns { url: "https://github.com/..." }
  async getOAuth2Url(provider: 'google' | 'github') {
    return this.request(`/api/v1/auth/oauth2/url/${provider}`)
  }

  async verifyToken() {
    try {
      return await this.request('/api/v1/auth/me')
    } catch (error) {
      console.error('Token verification failed:', error)
      localStorage.removeItem('auth_token')
      localStorage.removeItem('user')
      throw error
    }
  }

  // ─── Dashboard (not in Java backend — stub) ───────────────────────────────

  async getDashboardData() {
    return {
      clusters: 0,
      deployments: 0,
      pendingDeployments: 0,
      activeDeployments: 0,
      cpuUsage: 0,
      memoryUsage: 0,
      systemHealth: [] as Array<{ service: string; status: 'healthy' | 'warning' | 'error' }>,
      connectedRepositories: [],
      pullRequests: [],
    }
  }

  async getClusters() {
    return []
  }

  // ─── Deployments ──────────────────────────────────────────────────────────

  async getDeployments() {
    return this.request('/api/v1/deployments')
  }

  // ─── Repositories → /api/v1/repositories ─────────────────────────────────

  async getProjectIntegrations(): Promise<{ repositories?: any[]; items?: any[] }> {
    try {
      const data = await this.request<any[]>('/api/v1/repositories')
      const repositories = (data || []).map((r: any) => ({
        id: r.id,
        name: r.repo_name,
        fullName: `${r.owner}/${r.repo_name}`,
        connected: true,
        lastSync: r.updated_at || r.created_at,
        branch: 'main',
        autoDeployEnabled: false,
        webhookConfigured: false,
        htmlUrl: `https://github.com/${r.owner}/${r.repo_name}`,
      }))
      return { repositories }
    } catch {
      return { repositories: [] }
    }
  }

  async connectRepository(owner: string, repo: string) {
    // Java backend expects: { owner, repoName, gitUrl, cloudVendor } (snake_case on wire)
    return this.request('/api/v1/repositories', {
      method: 'POST',
      body: JSON.stringify({
        owner,
        repo_name: repo,
        git_url: `https://github.com/${owner}/${repo}`,
        cloud_vendor: 'NCP',
      }),
    })
  }

  // ─── Pull Requests (not in Java backend — stub) ───────────────────────────

  async getPullRequests(repository?: string) {
    return { repositories: [] }
  }

  // ─── Pipelines → redirect to /api/v1/deployments ─────────────────────────

  async getPipelines(repository?: string) {
    try {
      const repos = await this.request<any[]>('/api/v1/repositories')
      if (!repos || repos.length === 0) return { deployments: [] }

      const targetRepo = repository
        ? repos.find((r: any) => `${r.owner}/${r.repo_name}` === repository)
        : repos[0]

      if (!targetRepo) return { deployments: [] }

      const page = await this.request<any>(`/api/v1/deployments?repositoryId=${targetRepo.id}&size=20`)
      return { deployments: page?.content || [] }
    } catch {
      return { deployments: [] }
    }
  }

  // ─── Webhook config (not in Java backend — stub) ──────────────────────────

  async updateWebhookConfig(integrationId: number, enabled: boolean) {
    return { status: 'success' }
  }

  async getWebhookStatus(integrationId: number) {
    return { status: 'unknown' }
  }

  // ─── Trigger Deploy → POST /api/v1/deployments ───────────────────────────

  async triggerDeploy(owner: string, repo: string, branch: string = 'main') {
    // Look up the repository ID first
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) {
      throw new Error(`Repository ${owner}/${repo} not found. Please register it first.`)
    }
    return this.request('/api/v1/deployments', {
      method: 'POST',
      body: JSON.stringify({
        repository_id: targetRepo.id,
        branch_name: branch,
        commit_hash: 'HEAD',
      }),
    })
  }

  // ─── Deployment Histories → /api/v1/deployments ──────────────────────────

  async getDeploymentHistories(
    repository?: string,
    status?: string,
    limit: number = 20,
    offset: number = 0
  ) {
    try {
      const repos = await this.request<any[]>('/api/v1/repositories')
      let targetRepoId: number | null = null

      if (repository) {
        const [owner, repoName] = repository.split('/')
        const found = (repos || []).find(
          (r: any) => r.owner === owner && r.repo_name === repoName
        )
        if (found) targetRepoId = found.id
      } else if (repos && repos.length > 0) {
        targetRepoId = repos[0].id
      }

      if (!targetRepoId) return { deployments: [] }

      const page = await this.request<any>(
        `/api/v1/deployments?repositoryId=${targetRepoId}&size=${limit}`
      )

      const deployments = (page?.content || []).map((d: any) => ({
        id: d.id,
        repository: d.repository_name,
        status: mapDeploymentStatus(d.status),
        commit: {
          sha: d.commit_hash || '',
          short_sha: (d.commit_hash || '').substring(0, 7),
          message: '',
          author: '',
          url: '',
        },
        stages: {
          sourcecommit: { status: null, duration: null },
          sourcebuild: { status: null, duration: null },
          sourcedeploy: { status: null, duration: null },
        },
        image: { name: null, tag: null, url: null },
        cluster: { id: null, name: null, namespace: null },
        timing: {
          started_at: d.started_at || d.created_at,
          completed_at: d.finished_at,
          total_duration: null,
        },
        error: d.fail_reason ? { message: d.fail_reason, stage: null } : null,
        auto_deploy_enabled: false,
        created_at: d.created_at,
        updated_at: d.created_at,
      }))

      return { deployments }
    } catch {
      return { deployments: [] }
    }
  }

  async getDeploymentHistory(deploymentId: number) {
    return this.request(`/api/v1/deployments/${deploymentId}`)
  }

  async getRepositoryDeploymentHistories(
    owner: string,
    repo: string,
    status?: string,
    limit: number = 20,
    offset: number = 0
  ) {
    return this.getDeploymentHistories(`${owner}/${repo}`, status, limit, offset)
  }

  async getDeploymentStats(repository?: string, days: number = 30) {
    return { total: 0, success: 0, failed: 0 }
  }

  async getWebSocketStatus() {
    return { connected: false }
  }

  async getRepositoriesLatestDeployments(): Promise<{ repositories: any[] }> {
    try {
      const data = await this.request<any[]>('/api/v1/repositories')
      return { repositories: data || [] }
    } catch {
      return { repositories: [] }
    }
  }

  // ─── Deployment logs/pods (not fully in Java backend) ────────────────────

  async getDeploymentPods(namespace: string, app: string): Promise<any> {
    return { pods: [] }
  }

  async getDeploymentLogs(
    namespace: string,
    app: string,
    params: { pod?: string; lines?: number; previous?: boolean } = {}
  ): Promise<any> {
    return { logs: [] }
  }

  // ─── NLP ──────────────────────────────────────────────────────────────────

  async getCommandHistory(limit: number = 50, offset: number = 0): Promise<any[]> {
    return this.getConversationHistory(limit, offset)
  }

  async runCommand(payload: { text: string; context?: any }) {
    return this.request('/api/v1/nlp/command', {
      method: 'POST',
      body: JSON.stringify({ command: payload.text }),
    })
  }

  async getCommandSuggestions(context?: string) {
    return []
  }

  async sendConversationMessage(payload: {
    command: string
    session_id?: string
    timestamp: string
    context?: any
  }): Promise<any> {
    const response = await this.request<any>('/api/v1/nlp/command', {
      method: 'POST',
      body: JSON.stringify({
        command: payload.command,
        session_id: payload.session_id,
      }),
    })

    // Adapt Java backend response to shape expected by natural-language-command.tsx
    return {
      message: response?.message,
      result: response?.result,
      session_id: response?.session_id,
      command_log_id: response?.command_log_id,
      requires_confirmation: response?.requires_confirmation ?? false,
      pending_action: response?.requires_confirmation
        ? {
            type: response?.intent || 'unknown',
            parsed_intent: response?.intent || 'unknown',
            parameters: {},
            risk_level: response?.risk_level || 'LOW',
          }
        : null,
      cost_estimate: null,
    }
  }

  async confirmAction(payload: {
    commandLogId?: number
    session_id?: string
    confirmed: boolean
    user_response?: string
  }): Promise<any> {
    return this.request<any>('/api/v1/nlp/confirm', {
      method: 'POST',
      body: JSON.stringify({
        command_log_id: payload.commandLogId,
        confirmed: payload.confirmed,
      }),
    })
  }

  // Conversation history → /api/v1/nlp/history
  async getConversationHistory(limit: number = 50, offset: number = 0): Promise<any[]> {
    try {
      const page = await this.request<any>(
        `/api/v1/nlp/history?size=${limit}&page=${Math.floor(offset / Math.max(limit, 1))}`
      )
      const content: any[] = page?.content || []

      // Map CommandLogResponse to pairs of user + assistant messages
      const messages: any[] = []
      for (const cmd of content) {
        messages.push({
          id: cmd.id,
          tool: 'user_message',
          command_text: cmd.raw_command,
          created_at: cmd.created_at,
          result: null,
        })
        if (cmd.execution_result || cmd.error_message) {
          let parsedResult: any = null
          let displayText = cmd.error_message || ''

          if (cmd.execution_result) {
            try {
              parsedResult = JSON.parse(cmd.execution_result)
              displayText = parsedResult?.message || ''
            } catch {
              displayText = cmd.execution_result
            }
          }

          messages.push({
            id: `${cmd.id}_response`,
            tool: 'assistant_message',
            command_text: displayText,
            created_at: cmd.created_at,
            result: parsedResult,
          })
        }
      }
      return messages
    } catch {
      return []
    }
  }

  async listConversations(): Promise<any> {
    return { conversations: [] }
  }

  async deleteConversation(sessionId: string): Promise<any> {
    return {}
  }

  // ─── Rollback (not in Java backend — stub) ────────────────────────────────

  async getRollbackList(owner: string, repo: string): Promise<RollbackListResponse> {
    return {
      owner,
      repo,
      current_state: null,
      available_versions: [],
      total_available: 0,
      rollback_history: [],
      total_rollbacks: 0,
    }
  }

  async rollbackToCommit(owner: string, repo: string, commitSha: string): Promise<any> {
    return {}
  }

  async rollbackToPrevious(owner: string, repo: string, stepsBack: number = 1): Promise<any> {
    return {}
  }

  // ─── Scale / Restart → look up latest deployment ID ──────────────────────

  async scaleDeployment(owner: string, repo: string, replicas: number): Promise<any> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')

    const page = await this.request<any>(
      `/api/v1/deployments?repositoryId=${targetRepo.id}&size=1`
    )
    const latest = page?.content?.[0]
    if (!latest) throw new Error('No deployment found for this repository')

    return this.request(`/api/v1/deployments/${latest.id}/scale`, {
      method: 'POST',
      body: JSON.stringify({ replicas }),
    })
  }

  async restartDeployment(owner: string, repo: string): Promise<any> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')

    const page = await this.request<any>(
      `/api/v1/deployments?repositoryId=${targetRepo.id}&size=1`
    )
    const latest = page?.content?.[0]
    if (!latest) throw new Error('No deployment found for this repository')

    return this.request(`/api/v1/deployments/${latest.id}/restart`, {
      method: 'POST',
    })
  }

  // ─── Deployment Config → /api/v1/repositories/{id}/config ────────────────

  async getDeploymentConfig(owner: string, repo: string): Promise<DeploymentConfigResponse> {
    try {
      const repos = await this.request<any[]>('/api/v1/repositories')
      const targetRepo = (repos || []).find(
        (r: any) => r.owner === owner && r.repo_name === repo
      )
      if (!targetRepo) throw new Error('Repository not found')
      return this.request<DeploymentConfigResponse>(
        `/api/v1/repositories/${targetRepo.id}/config`
      )
    } catch {
      return {
        owner,
        repo,
        replica_count: 1,
        is_default: true,
        last_scaled_at: null,
        last_scaled_by: null,
        created_at: null,
        updated_at: null,
      }
    }
  }

  async updateDeploymentConfig(owner: string, repo: string, replicaCount: number): Promise<any> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')

    return this.request(`/api/v1/repositories/${targetRepo.id}/config`, {
      method: 'PUT',
      body: JSON.stringify({
        min_replicas: replicaCount,
        max_replicas: replicaCount,
        env_vars: {},
        container_port: 8080,
      }),
    })
  }

  async getScalingHistory(owner: string, repo: string, limit: number = 20): Promise<ScalingHistoryResponse> {
    try {
      const repos = await this.request<any[]>('/api/v1/repositories')
      const targetRepo = (repos || []).find(
        (r: any) => r.owner === owner && r.repo_name === repo
      )
      if (!targetRepo) {
        return { owner, repo, current_replicas: 1, history: [], total_count: 0 }
      }

      const page = await this.request<any>(
        `/api/v1/repositories/${targetRepo.id}/scaling-history?size=${limit}`
      )
      const content: any[] = page?.content || []

      const history = content.map((h: any) => ({
        deployment_id: h.deployment_id,
        replica_count: h.new_replicas,
        deployed_at: h.created_at,
        commit_sha_short: null,
        commit_message: null,
        status: 'completed',
      }))

      return {
        owner,
        repo,
        current_replicas: content[0]?.new_replicas ?? 1,
        history,
        total_count: page?.total_elements ?? content.length,
      }
    } catch {
      return { owner, repo, current_replicas: 1, history: [], total_count: 0 }
    }
  }

  // ─── Monitoring (not in Java backend — stub) ──────────────────────────────

  async getNKSOverview() { return {} }
  async getNKSCpuUsage() { return {} }
  async getNKSMemoryUsage() { return {} }
  async getNKSDiskUsage() { return {} }
  async getNKSNetworkTraffic() { return {} }
  async getMonitoringDetails() { return {} }
  async getNKSPodInfo() { return {} }

  // ─── Alerts (not in Java backend — stub) ──────────────────────────────────

  async getAlerts(cluster: string = 'nks-cluster') { return [] }
  async createAlertSnapshot(alertId: string, cluster: string = 'nks-cluster') { return {} }
  async getAlertReport(reportId: string) { return {} }
  async getAlertReports(alertId: string, limit: number = 20) { return [] }
  async resolveAlert(alertId: string, reason?: string) { return {} }

  // ─── Slack (not in Java backend — stub) ───────────────────────────────────

  async getSlackAuthUrl(redirectUri?: string) { return {} }
  async getSlackStatus() { return { connected: false } }

  // ─── MCP (not in Java backend — stub) ─────────────────────────────────────

  async sendMCPCommand(command: string) { return {} }
  async getMCPStatus() { return {} }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function mapDeploymentStatus(status: string): 'running' | 'success' | 'failed' {
  switch (status) {
    case 'PENDING':
    case 'UPLOADING_SOURCE':
    case 'BUILDING':
    case 'DEPLOYING':
      return 'running'
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
    case 'CANCELED':
      return 'failed'
    default:
      return 'running'
  }
}

// ─── Type exports ──────────────────────────────────────────────────────────────

export interface RollbackCandidate {
  steps_back: number
  commit_sha: string
  commit_sha_short: string
  commit_message: string
  deployed_at: string | null
  is_current: boolean
}

export interface RollbackListResponse {
  owner: string
  repo: string
  current_state: {
    commit_sha: string
    commit_sha_short: string
    commit_message: string
    deployed_at: string | null
    is_rollback: boolean
    deployment_id: number
  } | null
  available_versions: RollbackCandidate[]
  total_available: number
  rollback_history: Array<{
    commit_sha_short: string
    commit_message: string
    rolled_back_at: string | null
    rollback_from_id: number | null
  }>
  total_rollbacks: number
}

export interface DeploymentConfigResponse {
  owner: string
  repo: string
  replica_count: number
  is_default: boolean
  last_scaled_at: string | null
  last_scaled_by: string | null
  created_at: string | null
  updated_at: string | null
}

export interface ScalingHistoryResponse {
  owner: string
  repo: string
  current_replicas: number
  history: Array<{
    deployment_id: number
    replica_count: number
    deployed_at: string | null
    commit_sha_short: string | null
    commit_message: string | null
    status: string
  }>
  total_count: number
}

export const apiClient = new ApiClient()
export const api = apiClient
export default apiClient
