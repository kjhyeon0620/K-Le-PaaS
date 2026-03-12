import {
  DEFAULT_BASE_URL,
  getEnvironmentProfile,
  getProfile,
  loadConfig,
  saveConfig,
  upsertProfile,
} from "./config.mjs";

export class CliError extends Error {
  constructor(message, exitCode = 1, details = null) {
    super(message);
    this.name = "CliError";
    this.exitCode = exitCode;
    this.details = details;
  }
}

export class ApiClient {
  constructor({ profileName = "default", baseUrl = null } = {}) {
    this.profileName = profileName;
    this.baseUrlOverride = baseUrl;
  }

  async getOAuthUrl(provider = "github", redirectUri = null) {
    const search = redirectUri ? `?redirectUri=${encodeURIComponent(redirectUri)}` : "";
    return this.request(`/api/v1/auth/oauth2/url/${provider}${search}`, { auth: false });
  }

  async exchangeOAuthCode(code, redirectUri = null) {
    return this.request("/api/v1/auth/oauth2/login", {
      method: "POST",
      auth: false,
      body: {
        code,
        redirect_uri: redirectUri,
      },
    });
  }

  async getCurrentUser() {
    return this.request("/api/v1/auth/me");
  }

  async getSystemHealth() {
    return this.request("/api/v1/system/health", { auth: false });
  }

  async getSystemVersion() {
    return this.request("/api/v1/system/version", { auth: false });
  }

  async logout() {
    return this.request("/api/v1/auth/logout", { method: "POST" });
  }

  async runCommand(command) {
    return this.request("/api/v1/nlp/command", {
      method: "POST",
      body: { command },
    });
  }

  async confirmCommand(commandLogId, confirmed) {
    return this.request("/api/v1/nlp/confirm", {
      method: "POST",
      body: {
        command_log_id: commandLogId,
        confirmed,
      },
    });
  }

  async getHistory(page = 0, size = 20) {
    return this.request(`/api/v1/nlp/history?page=${page}&size=${size}`);
  }

  async getDeployments(repositoryId, page = 0, size = 20) {
    return this.request(`/api/v1/deployments?repositoryId=${repositoryId}&page=${page}&size=${size}`);
  }

  async getDeployment(deploymentId) {
    return this.request(`/api/v1/deployments/${deploymentId}`);
  }

  async getDeploymentStatus(deploymentId) {
    return this.request(`/api/v1/deployments/${deploymentId}/status`);
  }

  async restartDeployment(deploymentId) {
    return this.request(`/api/v1/deployments/${deploymentId}/restart`, {
      method: "POST",
    });
  }

  async scaleDeployment(deploymentId, replicas) {
    return this.request(`/api/v1/deployments/${deploymentId}/scale`, {
      method: "POST",
      body: { replicas },
    });
  }

  async getRepositories() {
    return this.request("/api/v1/repositories");
  }

  async getRepositoryConfig(repositoryId) {
    return this.request(`/api/v1/repositories/${repositoryId}/config`);
  }

  async costPlan(payload) {
    return this.request("/api/v1/cost/plan", { method: "POST", body: payload });
  }

  async costExplain(payload) {
    return this.request("/api/v1/cost/explain", { method: "POST", body: payload });
  }

  async costDiff(payload) {
    return this.request("/api/v1/cost/diff", { method: "POST", body: payload });
  }

  async costCheck(payload) {
    return this.request("/api/v1/cost/check", { method: "POST", body: payload });
  }

  async createCliAuthSession(payload) {
    return this.request("/api/v1/cli-auth/sessions", {
      method: "POST",
      auth: false,
      body: payload,
    });
  }

  async getCliAuthSession(sessionId) {
    return this.request(`/api/v1/cli-auth/sessions/${sessionId}`, {
      auth: false,
    });
  }

  async exchangeCliAuthSession(sessionId, userCode) {
    return this.request(`/api/v1/cli-auth/sessions/${sessionId}/exchange`, {
      method: "POST",
      auth: false,
      body: { userCode },
    });
  }

  async request(endpoint, { method = "GET", body, auth = true, retry = true } = {}) {
    const config = await loadConfig();
    const runtime = getEnvironmentProfile();
    const { name, profile } = getProfile(config, this.profileName, {
      baseUrl: this.baseUrlOverride,
    });
    const baseUrl = profile.baseUrl || DEFAULT_BASE_URL;
    const headers = {
      "Content-Type": "application/json",
      "Cache-Control": "no-cache",
      Pragma: "no-cache",
    };

    if (auth) {
      if (!profile.accessToken) {
        throw new CliError("로그인이 필요합니다. `klepaas auth login`을 먼저 실행하세요.", 2);
      }
      headers.Authorization = `Bearer ${profile.accessToken}`;
    }

    const response = await fetch(`${baseUrl}${endpoint}`, {
      method,
      headers,
      body: body ? JSON.stringify(toSnakeCase(body)) : undefined,
    });

    if (response.status === 401 && auth && retry && profile.refreshToken && !runtime.accessToken) {
      const refreshedProfile = await this.refreshProfile(baseUrl, config, name, profile.refreshToken);
      if (refreshedProfile.accessToken) {
        return this.request(endpoint, { method, body, auth, retry: false });
      }
    }

    const payload = await parsePayload(response);
    if (!response.ok) {
      throw mapApiError(response.status, payload);
    }

    if (payload && typeof payload === "object" && "status" in payload && "data" in payload) {
      if (payload.status === "success") {
        return payload.data;
      }
      throw new CliError(payload.message || "API 요청에 실패했습니다.", 3, payload);
    }

    return payload;
  }

  async refreshProfile(baseUrl, config, profileName, refreshToken) {
    const response = await fetch(`${baseUrl}/api/v1/auth/refresh`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });

    const payload = await parsePayload(response);
    if (!response.ok) {
      throw new CliError("토큰 갱신에 실패했습니다. 다시 로그인하세요.", 2, payload);
    }

    const data = payload?.data ?? payload;
    const nextConfig = upsertProfile(config, profileName, {
      baseUrl,
      accessToken: data.access_token,
      refreshToken: data.refresh_token || refreshToken,
    });
    await saveConfig(nextConfig);
    return getProfile(nextConfig, profileName).profile;
  }
}

function toSnakeCase(value) {
  if (Array.isArray(value)) {
    return value.map((item) => toSnakeCase(item));
  }

  if (value && typeof value === "object" && !(value instanceof Date)) {
    return Object.fromEntries(
      Object.entries(value).map(([key, nestedValue]) => [camelToSnake(key), toSnakeCase(nestedValue)])
    );
  }

  return value;
}

function camelToSnake(value) {
  return value.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
}

async function parsePayload(response) {
  const text = await response.text();
  if (!text) {
    return null;
  }

  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function mapApiError(status, payload) {
  const message =
    payload?.message ||
    payload?.error ||
    (typeof payload === "string" ? payload : `HTTP ${status}`);

  if (status === 401 || status === 403) {
    return new CliError(message, 2, payload);
  }
  if (status >= 400 && status < 500) {
    return new CliError(message, 1, payload);
  }
  return new CliError(message, 3, payload);
}
