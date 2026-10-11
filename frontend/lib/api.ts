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
        // 서버 ApiResponse의 message를 보여 준다. 호출부가 상태 코드로 분기하므로 코드는 남긴다
        let serverMessage: string | undefined
        try {
          serverMessage = JSON.parse(errorText)?.message
        } catch {
          serverMessage = undefined
        }
        throw new Error(serverMessage
          ? `${serverMessage} (HTTP ${response.status})`
          : `HTTP error! status: ${response.status} - ${errorText}`)
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
    return this.request('/api/v1/auth/oauth2/login', {
      method: 'POST',
      body: JSON.stringify({ code, redirect_uri: redirectUri }),
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

  async getCliAccessTokens() {
    return this.request<CliAccessTokenResponse[]>('/api/v1/cli-tokens')
  }

  async createCliAccessToken(payload: { name: string; expiresInDays: number; scope: CliTokenScope; repositoryId?: number }) {
    return this.request<CreateCliAccessTokenResponse>('/api/v1/cli-tokens', {
      method: 'POST',
      body: JSON.stringify({
        name: payload.name,
        expires_in_days: payload.expiresInDays,
        scope: payload.scope,
        repository_id: payload.repositoryId,
      }),
    })
  }

  async revokeCliAccessToken(tokenId: number) {
    return this.request('/api/v1/cli-tokens/' + tokenId, {
      method: 'DELETE',
    })
  }

  // OAuth2 URL generation — backend returns { url: "https://github.com/..." }
  async getOAuth2Url(
    provider: 'google' | 'github',
    options?: { redirectUri?: string; state?: string }
  ) {
    const search = new URLSearchParams()
    if (options?.redirectUri) search.set('redirectUri', options.redirectUri)
    if (options?.state) search.set('state', options.state)
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return this.request(`/api/v1/auth/oauth2/url/${provider}${suffix}`)
  }

  async createCliAuthSession(payload: {
    clientName: string
    hostname: string
    platform: string
    cliVersion: string
  }) {
    return this.request<CliAuthSessionResponse>('/api/v1/cli-auth/sessions', {
      method: 'POST',
      body: JSON.stringify({
        client_name: payload.clientName,
        hostname: payload.hostname,
        platform: payload.platform,
        cli_version: payload.cliVersion,
      }),
    })
  }

  async getCliAuthSession(sessionId: string) {
    return this.request<CliAuthSessionResponse>(`/api/v1/cli-auth/sessions/${sessionId}`)
  }

  async approveCliAuthSession(sessionId: string) {
    return this.request(`/api/v1/cli-auth/sessions/${sessionId}/approve`, {
      method: 'POST',
    })
  }

  async rejectCliAuthSession(sessionId: string) {
    return this.request(`/api/v1/cli-auth/sessions/${sessionId}/reject`, {
      method: 'POST',
    })
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

  // 등록 저장소와 저장소별 최신 배포 1건. 조회 실패는 빈 목록이 아니라 오류로 전달한다.
  async getRepositoriesLatestDeployments(): Promise<{ repositories: RepositoryWorkload[] }> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const repositories = await Promise.all(
      (repos || []).map(async (r: any): Promise<RepositoryWorkload> => {
        const page = await this.request<{ content?: DeploymentSummary[] }>(
          `/api/v1/deployments?repositoryId=${r.id}&size=1`
        )
        return {
          id: r.id,
          owner: r.owner,
          repo: r.repo_name,
          full_name: `${r.owner}/${r.repo_name}`,
          latest_deployment: page?.content?.[0] ?? null,
        }
      })
    )
    return { repositories }
  }

  // ─── Deployment history and request detail (#95) ─────────────────────────

  async getRepositoryDeployments(repositoryId: number, page: number = 0, size: number = 20): Promise<DeploymentPage> {
    return this.request<DeploymentPage>(`/api/v1/deployments?repositoryId=${repositoryId}&page=${page}&size=${size}`)
  }

  // 요청 기록, 적용 설정, 직전 성공 대비 변경, 실패 종류 설명. 기록이 없는 값은 null이다
  async getDeploymentDetail(deploymentId: number): Promise<DeploymentDetail> {
    return this.request<DeploymentDetail>(`/api/v1/deployments/${deploymentId}/detail`)
  }

  // ─── Replicas (#90) ──────────────────────────────────────────────────────

  // 저장소 앱의 현재 Deployment replica 관측값. 관측할 수 없으면 observation으로 구분하고 숫자는 null이다
  async getReplicaStatus(repositoryId: number): Promise<ReplicaStatus> {
    return this.request<ReplicaStatus>(`/api/v1/repositories/${repositoryId}/replicas`)
  }

  // ─── Deployment logs (#54) ──────────────────────────────────────────────

  // 이 배포 요청이 적용한 Pod의 상태·최근 로그·이벤트. 관측할 수 없으면 observation으로 구분한다
  async getDeploymentLogs(deploymentId: number, lines: number = 100): Promise<DeploymentLogs> {
    return this.request<DeploymentLogs>(`/api/v1/deployments/${deploymentId}/logs?lines=${lines}`)
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
            approval_target: response?.approval_target ?? null,
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

  // ─── 이전 배포로 복구 (#101) ────────────────────────────────────────────

  async getRecoveryCandidates(owner: string, repo: string): Promise<RecoveryCandidate[]> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')
    return this.request<RecoveryCandidate[]>(`/api/v1/repositories/${targetRepo.id}/recovery-candidates`)
  }

  async getRecoveryPlan(targetDeploymentId: number): Promise<RecoveryPlan> {
    return this.request<RecoveryPlan>(`/api/v1/deployments/${targetDeploymentId}/recovery-plan`)
  }

  // 확인한 계획의 지문을 보낸다. 계획이 바뀌었으면 서버가 409로 거절한다
  async recoverDeployment(targetDeploymentId: number, planFingerprint: string): Promise<DeploymentSummary> {
    return this.request<DeploymentSummary>(`/api/v1/deployments/${targetDeploymentId}/recover`, {
      method: 'POST',
      body: JSON.stringify({ plan_fingerprint: planFingerprint }),
    })
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

  // 조회 실패를 기본값으로 바꾸지 않는다. 기본값으로 PUT하면 기존 설정을 덮어쓴다 (#66).
  async getDeploymentConfig(owner: string, repo: string): Promise<DeploymentConfigResponse> {
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')
    const config = await this.request<DeploymentConfigResponse>(
      `/api/v1/repositories/${targetRepo.id}/config`
    )
    return normalizeDeploymentConfigResponse(owner, repo, config)
  }

  // 빌드·이미지·서비스 설정. 보내지 않은 항목(replica, envFrom, probe 등)은 현재 값을 유지한다.
  async updateDeploymentBuildAndService(
    owner: string,
    repo: string,
    settings: Pick<DeploymentConfigResponse,
      'build_strategy' | 'image_uri_template' | 'image_pull_secret_name' | 'service_type' | 'node_port' | 'container_port' | 'domain_url'>
  ): Promise<DeploymentConfigResponse> {
    return this.putDeploymentConfig(owner, repo, settings)
  }

  async updateDeploymentConfig(owner: string, repo: string, replicaCount: number): Promise<any> {
    return this.putDeploymentConfig(owner, repo, { min_replicas: replicaCount, max_replicas: replicaCount })
  }

  async updateDeploymentRuntimeEnvFrom(
    owner: string,
    repo: string,
    envFromConfigMaps: readonly string[],
    envFromSecrets: readonly string[]
  ): Promise<DeploymentConfigResponse> {
    return this.putDeploymentConfig(owner, repo, {
      env_from_config_maps: [...envFromConfigMaps],
      env_from_secrets: [...envFromSecrets],
    })
  }

  // health_probe·resources는 보낸 그룹 전체를 교체한다. probe path가 비어 있으면 probe를 해제한다.
  async updateDeploymentProbeAndResources(
    owner: string,
    repo: string,
    healthProbe: HealthProbe,
    resources: ContainerResources
  ): Promise<DeploymentConfigResponse> {
    return this.putDeploymentConfig(owner, repo, { health_probe: healthProbe, resources })
  }

  // false면 Pod에 imagePullSecrets를 넣지 않는다 (공개 이미지)
  async updateDeploymentImagePullSecretEnabled(
    owner: string,
    repo: string,
    enabled: boolean
  ): Promise<DeploymentConfigResponse> {
    return this.putDeploymentConfig(owner, repo, { image_pull_secret_enabled: enabled })
  }

  // 현재 설정에 overrides를 덮어 PUT한다. health_probe·resources는 overrides에 있을 때만 보내 서버 값을 보존한다.
  private async putDeploymentConfig(
    owner: string,
    repo: string,
    overrides: Partial<DeploymentConfigResponse>
  ): Promise<DeploymentConfigResponse> {
    const currentConfig = await this.getDeploymentConfig(owner, repo)
    const repos = await this.request<any[]>('/api/v1/repositories')
    const targetRepo = (repos || []).find(
      (r: any) => r.owner === owner && r.repo_name === repo
    )
    if (!targetRepo) throw new Error('Repository not found')

    const updatedConfig = await this.request<DeploymentConfigResponse>(`/api/v1/repositories/${targetRepo.id}/config`, {
      method: 'PUT',
      body: JSON.stringify({
        min_replicas: currentConfig.min_replicas,
        max_replicas: currentConfig.max_replicas,
        env_vars: currentConfig.env_vars,
        env_from_config_maps: currentConfig.env_from_config_maps,
        env_from_secrets: currentConfig.env_from_secrets,
        container_port: currentConfig.container_port,
        domain_url: currentConfig.domain_url,
        build_strategy: currentConfig.build_strategy,
        image_uri_template: currentConfig.image_uri_template,
        image_pull_secret_name: currentConfig.image_pull_secret_name,
        service_type: currentConfig.service_type,
        node_port: currentConfig.node_port,
        ...overrides,
      }),
    })
    return normalizeDeploymentConfigResponse(owner, repo, updatedConfig)
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

function mapDeploymentStatus(status: string): 'running' | 'success' | 'failed' | 'unknown' {
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
    case 'UNKNOWN':
      return 'unknown'
    default:
      return 'running'
  }
}

// ─── Type exports ──────────────────────────────────────────────────────────────

// GET /api/v1/repositories/{id}/recovery-candidates 항목 (#101)
export interface RecoveryCandidate {
  deployment_id: number
  commit_hash: string
  image_uri: string
  image_digest: string | null
  finished_at: string | null
  config_recorded: boolean
}

export type ConfigChange = DeploymentDetail['changes'][number]

// GET /api/v1/deployments/{id}/recovery-plan 응답 (#101)
export interface RecoveryPlan {
  target_deployment_id: number
  target_commit: string
  image: string
  image_digest: string | null
  digest_pinned: boolean
  config_changes: ConfigChange[]
  env_differences: ConfigChange[]
  executable: boolean
  blocked_reasons: string[]
  plan_fingerprint: string | null
}

export type DeploymentStatus =
  | 'PENDING' | 'UPLOADING_SOURCE' | 'BUILDING' | 'DEPLOYING' | 'SUCCESS' | 'FAILED' | 'CANCELED' | 'UNKNOWN'

// GET /api/v1/deployments 응답 항목 (DeploymentResponse)
export interface DeploymentSummary {
  id: number
  repository_id: number
  repository_name: string
  branch_name: string
  commit_hash: string
  image_uri: string | null
  status: DeploymentStatus
  fail_reason: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
  // 요청 기록 (#95). 이전 배포는 null
  trigger_source?: TriggerSource | null
  requested_by_user_id?: number | null
  command_log_id?: number | null
  image_digest?: string | null
  failure_kind?: FailureKind | null
  // 복구 배포가 되돌린 이전 성공 배포 (#101)
  recovered_from_deployment_id?: number | null
}

export type TriggerSource = 'WEB' | 'CLI' | 'CI_TOKEN' | 'CI_OIDC' | 'WEBHOOK' | 'NLP'
export type FailureKind =
  | 'IMAGE_PULL' | 'CRASH_LOOP' | 'CONFIG_ERROR' | 'READINESS_TIMEOUT' | 'UNSCHEDULABLE' | 'ROLLOUT_TIMEOUT'
  | 'PROGRESS_DEADLINE' | 'DEPLOYMENT_MISSING' | 'SUPERSEDED' | 'BUILD_FAILED' | 'APPLY_FAILED' | 'OTHER'

export interface DeploymentPage {
  content: DeploymentSummary[]
  total_elements: number
  total_pages: number
  number: number
}

// GET /api/v1/deployments/{id}/detail 응답 (DeploymentDetailResponse)
export interface DeploymentDetail {
  deployment: DeploymentSummary
  requested_by: { user_id: number; name: string | null } | null
  command: { id: number; raw_command: string | null; status: string | null } | null
  config: {
    build_strategy: string | null
    image_uri_template: string | null
    min_replicas: number
    max_replicas: number
    container_port: number
    domain_url: string | null
    service_type: string | null
    node_port: number | null
    env_names: string[]
    env_from_config_maps: string[]
    env_from_secrets: string[]
    image_pull_secret_enabled: boolean
    image_pull_secret_name: string | null
    health_probe: { path: string | null; port: number | null; period_seconds: number | null; failure_threshold: number | null } | null
    resources: { cpu_request: string | null; cpu_limit: string | null; memory_request: string | null; memory_limit: string | null } | null
  } | null
  previous_success: { id: number; commit_hash: string; image_uri: string | null; image_digest: string | null; finished_at: string | null } | null
  comparison: 'AVAILABLE' | 'NOT_RECORDED' | 'NO_PREVIOUS'
  changes: { field: string; before: string | null; after: string | null; kind: 'CHANGED' | 'ADDED' | 'REMOVED' | 'VALUE_CHANGED' | 'UNKNOWN' }[]
  explanation: { kind: FailureKind; title: string; summary: string; checks: string[] } | null
}

// GET /api/v1/repositories/{id}/replicas 응답 (ReplicaStatusResponse)
export interface ReplicaStatus {
  repository_id: number
  observation: 'AVAILABLE' | 'NOT_FOUND' | 'UNAVAILABLE'
  observation_message: string | null
  desired: number | null
  ready: number | null
  available: number | null
  updated: number | null
}

// GET /api/v1/deployments/{id}/logs 응답 (DeploymentLogResponse)
export type DeploymentObservation = 'AVAILABLE' | 'NOT_CURRENT' | 'UNAVAILABLE'

export interface DeploymentPodLog {
  name: string
  phase: string | null
  ready: boolean
  restart_count: number
  state: 'waiting' | 'running' | 'terminated' | null
  state_reason: string | null
  state_message: string | null
  last_termination_reason: string | null
  last_exit_code: number | null
  logs: string[]
  previous_logs: string[] | null
  logs_error: string | null
}

export interface DeploymentPodEvent {
  type: string | null
  reason: string | null
  message: string | null
  object: string
  count: number | null
  last_seen: string | null
}

export interface DeploymentLogs {
  deployment_id: number
  status: DeploymentStatus
  fail_reason: string | null
  observation: DeploymentObservation
  observation_message: string | null
  applied_deployment_id: number | null
  pods: DeploymentPodLog[]
  events: DeploymentPodEvent[]
}

export interface RepositoryWorkload {
  id: number
  owner: string
  repo: string
  full_name: string
  latest_deployment: DeploymentSummary | null
}

export interface DeploymentConfigResponse {
  id?: number
  repository_id?: number
  owner?: string
  repo?: string
  min_replicas: number
  max_replicas: number
  replica_count: number
  env_vars: Record<string, string>
  env_from_config_maps: string[]
  env_from_secrets: string[]
  container_port: number
  domain_url: string | null
  build_strategy?: string | null
  image_uri_template?: string | null
  image_pull_secret_name?: string | null
  service_type?: string | null
  node_port?: number | null
  health_probe?: HealthProbe | null
  resources?: ContainerResources | null
  image_pull_secret_enabled?: boolean
  is_default?: boolean
  last_scaled_at?: string | null
  last_scaled_by?: string | null
  created_at?: string | null
  updated_at?: string | null
}

export interface HealthProbe {
  path: string
  port: number | null
  initial_delay_seconds: number | null
  period_seconds: number | null
  failure_threshold: number | null
  startup_failure_threshold: number | null
}

export interface ContainerResources {
  cpu_request: string | null
  cpu_limit: string | null
  memory_request: string | null
  memory_limit: string | null
}

function normalizeDeploymentConfigResponse(
  owner: string,
  repo: string,
  config: Omit<DeploymentConfigResponse, 'replica_count'> & { readonly replica_count?: number }
): DeploymentConfigResponse {
  const maxReplicas = config.max_replicas ?? config.replica_count ?? 1
  const minReplicas = config.min_replicas ?? maxReplicas

  return {
    ...config,
    owner,
    repo,
    min_replicas: minReplicas,
    max_replicas: maxReplicas,
    replica_count: config.replica_count ?? maxReplicas,
    env_vars: config.env_vars ?? {},
    env_from_config_maps: config.env_from_config_maps ?? [],
    env_from_secrets: config.env_from_secrets ?? [],
    container_port: config.container_port ?? 8080,
    domain_url: config.domain_url ?? null,
  }
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

export type CliTokenScope = 'READ_ONLY' | 'PROPOSE_ONLY' | 'DEPLOY' | 'FULL'

export const CLI_TOKEN_SCOPE_LABELS: Record<CliTokenScope, string> = {
  READ_ONLY: '조회 전용',
  PROPOSE_ONLY: '제안 전용',
  DEPLOY: '배포 전용',
  FULL: '전체 권한',
}

export interface CliAccessTokenResponse {
  id: number
  name: string
  token_prefix: string
  scope: CliTokenScope
  repository_id: number | null
  expires_at: string
  last_used_at: string | null
  revoked_at: string | null
  created_at: string
}

export interface CreateCliAccessTokenResponse {
  token: string
  metadata: CliAccessTokenResponse
}

export interface CliAuthSessionResponse {
  session_id: string
  user_code: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CONSUMED'
  client_name: string
  hostname: string
  platform: string
  cli_version: string
  scope: CliTokenScope
  expires_at: string
  poll_interval_seconds: number
  verification_url: string
}

export const apiClient = new ApiClient()
export const api = apiClient
export default apiClient
