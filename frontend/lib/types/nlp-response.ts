/**
 * NLP Response Types
 * 
 * 자연어 명령어 응답의 타입 정의입니다.
 * 백엔드 ResponseFormatter에서 생성하는 구조화된 응답과 일치합니다.
 */

export type NLPResponseType =
  | 'list_pods'
  | 'list_rollback'
  | 'status'
  | 'pod_status'
  | 'service_status'
  | 'deployment_status'
  | 'logs'
  | 'list_deployments'
  | 'list_services'
  | 'list_ingresses'
  | 'list_namespaces'
  | 'list_endpoints'
  | 'get_service'
  | 'get_deployment'
  | 'overview'
  | 'endpoint'
  | 'scale'
  | 'deploy'
  | 'deploy_github_repository'
  | 'restart'
  | 'rollback'
  | 'cost_analysis'
  | 'rollback_execution'
  | 'list_commands'
  | 'unknown'
  | 'error'
  | 'command_error'

export interface FormattedResponse {
  type: NLPResponseType
  message: string
  summary: string
  data: {
    formatted: any
    raw?: any
  }
  metadata?: Record<string, any>
}

// Pod 관련 타입
export interface PodInfo {
  name: string
  status: string
  ready: string
  restarts: number
  age: string
  node: string
  namespace: string
}

export interface PodListMetadata {
  total: number
  namespace: string
  running: number
  pending: number
  failed: number
}

// 롤백 관련 타입
export interface RollbackVersion {
  steps_back: number
  commit: string
  message: string
  date: string
  can_rollback: boolean
  is_current?: boolean
}

export interface RollbackCurrent {
  commit: string
  message: string
  date: string
  is_rollback: boolean
  deployment_id?: number
}

export interface RollbackHistory {
  commit: string
  message: string
  date: string
  rollback_from_id?: number
}

export interface RollbackListData {
  current: RollbackCurrent
  versions: RollbackVersion[]
  history: RollbackHistory[]
}

export interface RollbackListMetadata {
  owner: string
  repo: string
  total_available: number
  total_rollbacks: number
}

// Service 관련 타입
export interface ServiceInfo {
  name: string
  namespace: string
  type: string
  cluster_ip: string
  external_ip: string
  ports: string
  age: string
}

export interface ServiceEndpoint {
  type: string
  address: string
  port: string
  protocol: string
}

export interface ServiceEndpointsData {
  service_name: string
  namespace: string
  endpoints: ServiceEndpoint[]
}

// Deployment 관련 타입 (NLP LIST_DEPLOYMENTS: 배포 이력 레코드)
export interface DeploymentInfo {
  id: number
  name: string
  status: string
  branch: string | null
  commit: string | null
  image: string | null
  fail_reason: string | null
  created_at: string | null
}

// Ingress 관련 타입
export interface IngressInfo {
  name: string
  namespace: string
  class: string
  hosts: string[]
  urls?: string[]
  has_tls?: boolean
  service_name?: string
  port?: number | string
  path?: string
  address: string
  ports: string | object
  age: string
}

// Namespace 관련 타입
export interface NamespaceInfo {
  name: string
  status: string
  age: string
}

// 로그 관련 타입
export interface LogsData {
  pod_name: string
  namespace: string
  lines: number
  log_lines: string[]
  total_lines: number
}

export interface LogsMetadata {
  namespace: string
  lines_requested: number
  lines_returned: number
}

// 비용 분석 관련 타입
export interface CostOptimization {
  type: string
  description: string
  potential_savings: number
}

export interface CostAnalysisData {
  current_cost: number
  optimizations: CostOptimization[]
}

export interface CostAnalysisMetadata {
  current_cost: number
  optimization_count: number
}

// 스케일링 관련 타입
export interface ScaleData {
  repository: string
  old_replicas: number
  new_replicas: number
  change: string
  status: string
  timestamp: string
  action: string
}

export interface ScaleMetadata {
  owner: string
  repo: string
  old_replicas: number
  new_replicas: number
  status: string
}

// 배포 관련 타입
export interface DeployData {
  app_name: string
  environment: string
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
  [key: string]: any
}

export interface DeployMetadata {
  app_name: string
  environment: string
}

// 재시작 관련 타입
export interface RestartData {
  name: string
  namespace: string
  [key: string]: any
}

export interface RestartMetadata {
  name: string
  namespace: string
}

// 상태 관련 타입
export interface StatusData {
  name: string
  namespace: string
  status: string
  ready: string
  restarts: number
  age: string
  node: string
}

export interface StatusMetadata {
  namespace: string
  is_healthy: boolean
}

// Pod Status 관련 타입
export interface PodStatusData {
  namespace: string
  label_selector: string
  total_pods: number
  running: number
  pending: number
  failed: number
  pods: Array<{
    name: string
    phase: string
    ready: string
    restarts: number
    node: string
    labels: Record<string, string>
    creation_timestamp: string
  }>
}

export interface PodStatusMetadata {
  namespace: string
  total_pods: number
  running: number
  pending: number
  failed: number
  is_healthy: boolean
}

// Service Status 관련 타입
export interface ServiceStatusData {
  name: string
  namespace: string
  type: string
  cluster_ip: string
  ports: Array<{
    port: number
    target_port: string
    protocol: string
  }>
  selector: Record<string, string>
  ready_endpoints: number
  creation_timestamp: string | null
}

export interface ServiceStatusMetadata {
  namespace: string
  is_healthy: boolean
}

// Deployment Status 관련 타입
export interface DeploymentStatusData {
  name: string
  namespace: string
  replicas: {
    desired: number
    current: number
    ready: number
    available: number
  }
  conditions: Array<{
    type: string
    status: string
    reason: string
    message: string
  }>
  pods: Array<{
    name: string
    phase: string
    ready: string
    restarts: number
    node: string
  }>
  creation_timestamp: string | null
}

export interface DeploymentStatusMetadata {
  namespace: string
  desired: number
  ready: number
  is_healthy: boolean
}

// 엔드포인트 관련 타입
export interface EndpointData {
  service_name: string
  namespace: string
  endpoints: ServiceEndpoint[]
}

export interface EndpointMetadata {
  namespace: string
  total_endpoints: number
}

// 엔드포인트 목록 관련 타입
export interface EndpointListInfo {
  service_name: string
  service_type: string
  cluster_ip: string
  ports: Array<{
    port: number | string
    target_port: number | string
    protocol: string
    node_port?: number
  }>
  service_endpoint?: string
  ingress_domains: Array<{
    domain: string
    path: string
    ingress_name?: string
  }>
  external_access?: {
    type: string
    address: string
    ports: Array<{
      port: number | string
      protocol: string
    }>
  }
}

export interface EndpointListMetadata {
  namespace: string
  total_services: number
  services_with_ingress: number
  services_with_external: number
}

// 통합 대시보드 관련 타입
export interface ReportSection {
  title: string
  type: string
  items?: any[]
  data?: any
}

export interface OverviewData {
  report_sections: ReportSection[]
  summary?: {
    cluster_name: string
    total_nodes: number
    total_namespaces: number
    total_deployments: number
    total_pods: number
    total_services: number
    running_pods?: number
    critical_deployment_issues?: number
    pending_pod_issues?: number
    failed_pod_issues?: number
    high_restart_issues?: number
  }
}

export interface OverviewMetadata {
  cluster_name: string
  total_nodes: number
  total_namespaces: number
  total_deployments: number
  total_pods: number
  total_services: number
  critical_issues_count: number
  warnings_count: number
}

// 에러 관련 타입
export interface ErrorData {
  error: string
  command: string
  error_message?: string
}

export interface ErrorMetadata {
  command: string
  has_error: boolean
}

// 명령어 에러 관련 타입
export interface CommandErrorData {
  error_type: string
  error_message: string
  solutions?: Array<{
    title: string
    description?: string
    example?: string
  }>
  supported_commands?: Array<{
    category: string
    name: string
    example: string
  }>
  technical_details?: string
  [key: string]: any
}

export interface CommandErrorMetadata {
  error_type: string
  timestamp: string
  command?: string
  [key: string]: any
}

// 알 수 없는 명령어 관련 타입
export interface UnknownData {
  [key: string]: any
}

export interface UnknownMetadata {
  command: string
}

// 타입별 응답 데이터 유니온 타입
export type ResponseData = 
  | PodInfo[]
  | RollbackListData
  | StatusData
  | PodStatusData
  | ServiceStatusData
  | DeploymentStatusData
  | LogsData
  | ServiceInfo[]
  | DeploymentInfo[]
  | IngressInfo[]
  | NamespaceInfo[]
  | EndpointListInfo[]
  | ServiceEndpointsData
  | OverviewData
  | ScaleData
  | DeployData
  | RestartData
  | EndpointData
  | CostAnalysisData
  | RollbackExecutionData
  | ErrorData
  | CommandErrorData
  | UnknownData

// 타입별 메타데이터 유니온 타입
export type ResponseMetadata = 
  | PodListMetadata
  | RollbackListMetadata
  | StatusMetadata
  | PodStatusMetadata
  | ServiceStatusMetadata
  | DeploymentStatusMetadata
  | LogsMetadata
  | EndpointMetadata
  | EndpointListMetadata
  | OverviewMetadata
  | ScaleMetadata
  | DeployMetadata
  | RestartMetadata
  | CostAnalysisMetadata
  | RollbackExecutionMetadata
  | ErrorMetadata
  | CommandErrorMetadata
  | UnknownMetadata
  | Record<string, any>

// 타입별 응답 인터페이스
export interface PodListResponse extends FormattedResponse {
  type: 'list_pods'
  data: {
    formatted: PodInfo[]
    raw?: any
  }
  metadata: PodListMetadata
}

export interface RollbackListResponse extends FormattedResponse {
  type: 'list_rollback'
  data: {
    formatted: RollbackListData
    raw?: any
  }
  metadata: RollbackListMetadata
}

export interface StatusResponse extends FormattedResponse {
  type: 'status'
  data: {
    formatted: StatusData
    raw?: any
  }
  metadata: StatusMetadata
}

export interface PodStatusResponse extends FormattedResponse {
  type: 'pod_status'
  data: {
    formatted: PodStatusData
    raw?: any
  }
  metadata: PodStatusMetadata
}

export interface ServiceStatusResponse extends FormattedResponse {
  type: 'service_status'
  data: {
    formatted: ServiceStatusData
    raw?: any
  }
  metadata: ServiceStatusMetadata
}

export interface DeploymentStatusResponse extends FormattedResponse {
  type: 'deployment_status'
  data: {
    formatted: DeploymentStatusData
    raw?: any
  }
  metadata: DeploymentStatusMetadata
}

export interface LogsResponse extends FormattedResponse {
  type: 'logs'
  data: {
    formatted: LogsData
    raw?: any
  }
  metadata: LogsMetadata
}

export interface ServiceListResponse extends FormattedResponse {
  type: 'list_services'
  data: {
    formatted: ServiceInfo[]
    raw?: any
  }
  metadata: Record<string, any>
}

export interface DeploymentListResponse extends FormattedResponse {
  type: 'list_deployments'
  data: {
    formatted: DeploymentInfo[]
    raw?: any
  }
  metadata: Record<string, any>
}

export interface IngressListResponse extends FormattedResponse {
  type: 'list_ingresses'
  data: {
    formatted: IngressInfo[]
    raw?: any
  }
  metadata: Record<string, any>
}

export interface NamespaceListResponse extends FormattedResponse {
  type: 'list_namespaces'
  data: {
    formatted: NamespaceInfo[]
    raw?: any
  }
  metadata: Record<string, any>
}

export interface ServiceDetailResponse extends FormattedResponse {
  type: 'get_service'
  data: {
    formatted: any
    raw?: any
  }
  metadata: Record<string, any>
}

export interface DeploymentDetailResponse extends FormattedResponse {
  type: 'get_deployment'
  data: {
    formatted: any
    raw?: any
  }
  metadata: Record<string, any>
}

export interface OverviewResponse extends FormattedResponse {
  type: 'overview'
  data: {
    formatted: OverviewData
    raw?: any
  }
  metadata: OverviewMetadata
}

export interface EndpointResponse extends FormattedResponse {
  type: 'endpoint'
  data: {
    formatted: EndpointData
    raw?: any
  }
  metadata: EndpointMetadata
}

export interface EndpointListResponse extends FormattedResponse {
  type: 'list_endpoints'
  data: {
    formatted: EndpointListInfo[]
    raw?: any
  }
  metadata: EndpointListMetadata
}

export interface ScaleResponse extends FormattedResponse {
  type: 'scale'
  data: {
    formatted: ScaleData
    raw?: any
  }
  metadata: ScaleMetadata
}

export interface DeployResponse extends FormattedResponse {
  type: 'deploy'
  data: {
    formatted: DeployData
    raw?: any
  }
  metadata: DeployMetadata
}

export interface DeployGitHubRepositoryResponse extends FormattedResponse {
  type: 'deploy_github_repository'
  data: {
    formatted: DeployData
    raw?: any
  }
  metadata: DeployMetadata
}

export interface RestartResponse extends FormattedResponse {
  type: 'restart'
  data: {
    formatted: RestartData
    raw?: any
  }
  metadata: RestartMetadata
}

export interface CostAnalysisResponse extends FormattedResponse {
  type: 'cost_analysis'
  data: {
    formatted: CostAnalysisData
    raw?: any
  }
  metadata: CostAnalysisMetadata
}

// 롤백 실행 관련 타입
export interface RollbackExecutionData {
  action_type: string
  action_description: string
  project: string
  target_commit: string
  status: string
  timestamp: string
  details: {
    owner: string
    repo: string
    target_commit_full: string
    action: string
    status: string
  }
}

export interface RollbackExecutionMetadata {
  owner: string
  repo: string
  action_type: string
  target_commit: string
  status: string
}

// 롤백 실행 결과 타입
export interface RollbackData {
  status: string
  target_commit: string
  previous_commit: string
  owner: string
  repo: string
}

export interface RollbackResponse extends FormattedResponse {
  type: 'rollback'
  data: {
    formatted: RollbackData
    raw?: any
  }
  metadata: {
    owner: string
    repo: string
    target_commit: string
  }
}

export interface RollbackExecutionResponse extends FormattedResponse {
  type: 'rollback_execution'
  data: {
    formatted: RollbackExecutionData
    raw?: any
  }
  metadata: RollbackExecutionMetadata
}

export interface ErrorResponse extends FormattedResponse {
  type: 'error'
  data: {
    formatted: ErrorData
    raw?: any
  }
  metadata: ErrorMetadata
}

export interface CommandErrorResponse extends FormattedResponse {
  type: 'command_error'
  data: {
    formatted: CommandErrorData
    raw?: any
  }
  metadata: CommandErrorMetadata
}

export interface UnknownResponse extends FormattedResponse {
  type: 'unknown'
  data: {
    formatted: UnknownData
    raw?: any
  }
  metadata: UnknownMetadata
}

// 명령어 목록 관련 타입
export interface CommandInfo {
  name: string
  name_ko: string
  desc: string
  example: string
}

export interface CommandCategory {
  category: string
  icon: string
  commands: CommandInfo[]
}

export interface ListCommandsData {
  categories: CommandCategory[]
  total_commands: number
  help_text: string[]
}

export interface ListCommandsMetadata {
  total_commands: number
  category_count: number
}

export interface ListCommandsResponse extends FormattedResponse {
  type: 'list_commands'
  data: {
    formatted: ListCommandsData
    raw?: any
  }
  metadata: ListCommandsMetadata
}

// 모든 응답 타입의 유니온
export type NLPResponse = 
  | PodListResponse
  | RollbackListResponse
  | StatusResponse
  | PodStatusResponse
  | ServiceStatusResponse
  | DeploymentStatusResponse
  | LogsResponse
  | ServiceListResponse
  | DeploymentListResponse
  | IngressListResponse
  | NamespaceListResponse
  | EndpointListResponse
  | ServiceDetailResponse
  | DeploymentDetailResponse
  | OverviewResponse
  | EndpointResponse
  | ScaleResponse
  | DeployResponse
  | DeployGitHubRepositoryResponse
  | RestartResponse
  | RollbackResponse
  | CostAnalysisResponse
  | RollbackExecutionResponse
  | ListCommandsResponse
  | ErrorResponse
  | CommandErrorResponse
  | UnknownResponse
