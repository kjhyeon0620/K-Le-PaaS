#!/usr/bin/env node

import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import readline from "node:readline/promises";
import { spawn } from "node:child_process";

import { ApiClient, CliError } from "./api.mjs";
import {
  DEFAULT_BASE_URL,
  getConfigPath,
  getEnvironmentProfile,
  getProfile,
  loadConfig,
  saveConfig,
  upsertProfile,
} from "./config.mjs";
import { formatCurrency, printJson, printKeyValues, printRows, printYaml } from "./output.mjs";

const EXIT_CODES = {
  SUCCESS: 0,
  INPUT: 1,
  AUTH: 2,
  API: 3,
  COST_LIMIT: 4,
  TIMEOUT: 5,
};

async function main() {
  const { globalOptions, rest } = parseGlobalOptions(process.argv.slice(2));
  const command = rest.shift();

  if (!command || command === "help" || globalOptions.help) {
    printHelp();
    return;
  }

  const client = new ApiClient({
    profileName: globalOptions.profile,
    baseUrl: globalOptions.baseUrl,
  });

  switch (command) {
    case "auth":
      await handleAuth(rest, globalOptions, client);
      return;
    case "ask":
      await handleAsk(rest, globalOptions, client);
      return;
    case "confirm":
      await handleConfirm(rest, globalOptions, client);
      return;
    case "history":
      await handleHistory(rest, globalOptions, client);
      return;
    case "deployments":
      await handleDeployments(rest, globalOptions, client);
      return;
    case "cost":
      await handleCost(rest, globalOptions, client);
      return;
    case "doctor":
      await handleDoctor(globalOptions, client);
      return;
    default:
      throw new CliError(`지원하지 않는 명령입니다: ${command}`, EXIT_CODES.INPUT);
  }
}

async function handleAuth(args, globalOptions, client) {
  const subcommand = args.shift();
  switch (subcommand) {
    case "login":
      await handleAuthLogin(args, globalOptions, client);
      return;
    case "whoami":
      await handleWhoAmI(globalOptions, client);
      return;
    case "logout":
      await handleLogout(globalOptions, client);
      return;
    default:
      throw new CliError("`auth` 하위 명령은 `login`, `whoami`, `logout` 중 하나여야 합니다.", EXIT_CODES.INPUT);
  }
}

async function handleAuthLogin(args, globalOptions, client) {
  const useWeb = takeFlag(args, "--web");
  const code = takeOption(args, "--code");
  const accessToken = takeOption(args, "--token");
  const refreshToken = takeOption(args, "--refresh-token");
  const redirectUri = takeOption(args, "--redirect-uri");

  assertNoUnknownOptions(args);

  if (useWeb) {
    await loginWithBrowser(globalOptions, client);
    return;
  }

  if (code) {
    const tokens = await client.exchangeOAuthCode(code, redirectUri);
    await saveProfileTokens(globalOptions, tokens, resolveBaseUrl(globalOptions), client);
    return;
  }

  if (!accessToken) {
    throw new CliError("`auth login`은 `--token`, `--code`, `--web` 중 하나가 필요합니다.", EXIT_CODES.INPUT);
  }

  await saveProfileTokens(
    globalOptions,
    {
      access_token: accessToken,
      refresh_token: refreshToken,
    },
    resolveBaseUrl(globalOptions),
    client
  );
}

async function loginWithBrowser(globalOptions, client) {
  const session = await client.createCliAuthSession({
    clientName: "KLEPaaS CLI",
    hostname: os.hostname(),
    platform: `${process.platform}/${process.arch}`,
    cliVersion: "1.0.0",
  });

  console.log("브라우저에서 KLEPaaS CLI 로그인을 승인하세요.");
  console.log(`승인 URL: ${session.verification_url}`);
  console.log(`User Code: ${session.user_code}`);
  console.log("브라우저가 열리지 않으면 위 URL을 직접 열어 승인하세요.");
  openBrowser(session.verification_url);

  const deadline = Date.now() + 5 * 60 * 1000;
  while (Date.now() < deadline) {
    const latest = await client.getCliAuthSession(session.session_id);

    if (latest.status === "APPROVED") {
      const exchanged = await client.exchangeCliAuthSession(latest.session_id, latest.user_code);
      await saveProfileTokens(
        globalOptions,
        {
          access_token: exchanged.token,
          refresh_token: null,
        },
        resolveBaseUrl(globalOptions),
        client
      );
      return;
    }

    if (latest.status === "REJECTED") {
      throw new CliError("웹에서 CLI 로그인 요청이 거부되었습니다.", EXIT_CODES.AUTH);
    }

    if (latest.status === "EXPIRED") {
      throw new CliError("CLI 로그인 세션이 만료되었습니다. 다시 시도하세요.", EXIT_CODES.TIMEOUT);
    }

    if (latest.status === "CONSUMED") {
      throw new CliError("이 CLI 로그인 세션은 이미 사용되었습니다.", EXIT_CODES.AUTH);
    }

    await sleep((latest.poll_interval_seconds || 3) * 1000);
  }

  throw new CliError("CLI 로그인 시간이 초과되었습니다.", EXIT_CODES.TIMEOUT);
}

async function handleWhoAmI(globalOptions, client) {
  const user = await client.getCurrentUser();
  if (globalOptions.json) {
    printJson(user);
    return;
  }

  printKeyValues([
    ["ID", user.id],
    ["Name", user.name],
    ["Email", user.email],
    ["Role", user.role],
  ]);
}

async function handleLogout(globalOptions, client) {
  const config = await loadConfig();
  const { name, profile } = getProfile(config, globalOptions.profile);

  if (profile.accessToken) {
    try {
      await client.logout();
    } catch {}
  }

  const nextConfig = upsertProfile(config, name, {
    accessToken: null,
    refreshToken: null,
    user: null,
  });
  await saveConfig(nextConfig);

  if (!globalOptions.quiet) {
    console.log(`로그아웃되었습니다. 설정 파일: ${getConfigPath()}`);
  }
}

async function handleAsk(args, globalOptions, client) {
  const command = args.join(" ").trim();
  if (!command) {
    throw new CliError("실행할 자연어 명령이 필요합니다.", EXIT_CODES.INPUT);
  }

  const response = await client.runCommand(command);
  if (globalOptions.json) {
    printJson(response);
    return;
  }

  printKeyValues([
    ["Command Log", response.command_log_id ?? "-"],
    ["Intent", response.intent ?? "-"],
    ["Risk", response.risk_level ?? "-"],
    ["Needs Confirm", response.requires_confirmation ? "yes" : "no"],
  ]);
  if (response.message) {
    console.log(`\n${response.message}`);
  }
  if (response.result != null) {
    console.log("\nResult:");
    printJson(response.result);
  }
}

async function handleConfirm(args, globalOptions, client) {
  const commandLogId = Number(args.shift());
  if (!Number.isFinite(commandLogId)) {
    throw new CliError("확인할 command_log_id가 필요합니다.", EXIT_CODES.INPUT);
  }

  const approved = takeFlag(args, "--yes");
  const rejected = takeFlag(args, "--no");
  assertNoUnknownOptions(args);

  let confirmed = approved;
  if (!approved && !rejected) {
    confirmed = await askConfirmation(`command_log_id=${commandLogId} 명령을 실행할까요?`);
  } else if (rejected) {
    confirmed = false;
  }

  const response = await client.confirmCommand(commandLogId, confirmed);
  if (globalOptions.json) {
    printJson(response);
    return;
  }

  console.log(response.message || (confirmed ? "명령을 실행했습니다." : "명령 실행을 취소했습니다."));
  if (response.result != null) {
    console.log("\nResult:");
    printJson(response.result);
  }
}

async function handleHistory(args, globalOptions, client) {
  const page = Number(takeOption(args, "--page") || "0");
  const size = Number(takeOption(args, "--size") || "20");
  assertNoUnknownOptions(args);

  const response = await client.getHistory(page, size);
  if (globalOptions.json) {
    printJson(response);
    return;
  }

  const rows = response.content ?? [];
  if (rows.length === 0) {
    console.log("명령 이력이 없습니다.");
    return;
  }

  printRows(rows, [
    { key: "id", label: "ID" },
    { key: "intent", label: "Intent" },
    { key: "risk_level", label: "Risk" },
    { key: "is_executed", label: "Executed" },
    { key: "created_at", label: "Created At" },
    { key: (row) => truncate(row.raw_command, 32), label: "Command" },
  ]);
}

async function handleDeployments(args, globalOptions, client) {
  const subcommand = args.shift();
  switch (subcommand) {
    case "list": {
      const repositoryIdValue = takeOption(args, "--repository-id");
      const repositoryId = Number(repositoryIdValue);
      const page = Number(takeOption(args, "--page") || "0");
      const size = Number(takeOption(args, "--size") || "20");
      assertNoUnknownOptions(args);

      if (!repositoryIdValue || !Number.isFinite(repositoryId)) {
        throw new CliError("`deployments list`에는 `--repository-id`가 필요합니다.", EXIT_CODES.INPUT);
      }

      const response = await client.getDeployments(repositoryId, page, size);
      if (globalOptions.json) {
        printJson(response);
        return;
      }

      const rows = response.content ?? [];
      if (rows.length === 0) {
        console.log("배포가 없습니다.");
        return;
      }

      printRows(rows, [
        { key: "id", label: "ID" },
        { key: "repository_name", label: "Repository" },
        { key: "status", label: "Status" },
        { key: "branch_name", label: "Branch" },
        { key: "commit_hash", label: "Commit" },
        { key: "created_at", label: "Created At" },
      ]);
      return;
    }
    case "get": {
      const deploymentId = Number(args.shift());
      assertNoUnknownOptions(args);
      if (!Number.isFinite(deploymentId)) {
        throw new CliError("조회할 deployment_id가 필요합니다.", EXIT_CODES.INPUT);
      }

      const response = await client.getDeployment(deploymentId);
      if (globalOptions.json) {
        printJson(response);
        return;
      }

      printKeyValues([
        ["ID", response.id],
        ["Repository", response.repository_name],
        ["Status", response.status],
        ["Branch", response.branch_name],
        ["Commit", response.commit_hash],
        ["Image", response.image_uri || "-"],
        ["Started", response.started_at || "-"],
        ["Finished", response.finished_at || "-"],
        ["Fail Reason", response.fail_reason || "-"],
      ]);
      return;
    }
    case "restart": {
      const deploymentId = Number(args.shift());
      assertNoUnknownOptions(args);
      if (!Number.isFinite(deploymentId)) {
        throw new CliError("재시작할 deployment_id가 필요합니다.", EXIT_CODES.INPUT);
      }

      const response = await client.restartDeployment(deploymentId);
      if (globalOptions.json) {
        printJson(response);
        return;
      }

      console.log(response?.message || "재시작 요청이 접수되었습니다.");
      return;
    }
    case "scale": {
      const deploymentId = Number(args.shift());
      const replicasValue = takeOption(args, "--replicas");
      const replicas = Number(replicasValue);
      assertNoUnknownOptions(args);

      if (!Number.isFinite(deploymentId) || !replicasValue || !Number.isFinite(replicas)) {
        throw new CliError("`deployments scale <id> --replicas <n>` 형식이 필요합니다.", EXIT_CODES.INPUT);
      }

      const response = await client.scaleDeployment(deploymentId, replicas);
      if (globalOptions.json) {
        printJson(response);
        return;
      }

      console.log(response?.message || "스케일링 요청이 접수되었습니다.");
      return;
    }
    case "wait": {
      const deploymentId = Number(args.shift());
      const timeoutSeconds = Number(takeOption(args, "--timeout") || "600");
      const intervalSeconds = Number(takeOption(args, "--interval") || "5");
      assertNoUnknownOptions(args);

      if (!Number.isFinite(deploymentId)) {
        throw new CliError("대기할 deployment_id가 필요합니다.", EXIT_CODES.INPUT);
      }
      if (!Number.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
        throw new CliError("`--timeout`은 1초 이상의 숫자여야 합니다.", EXIT_CODES.INPUT);
      }
      if (!Number.isFinite(intervalSeconds) || intervalSeconds <= 0) {
        throw new CliError("`--interval`은 1초 이상의 숫자여야 합니다.", EXIT_CODES.INPUT);
      }

      await waitForDeployment(client, deploymentId, {
        timeoutSeconds,
        intervalSeconds,
        json: globalOptions.json,
      });
      return;
    }
    case "export": {
      const deploymentId = Number(args.shift());
      const format = (takeOption(args, "--format") || "json").toLowerCase();
      const outputPath = takeOption(args, "--output");
      assertNoUnknownOptions(args);

      if (!Number.isFinite(deploymentId)) {
        throw new CliError("내보낼 deployment_id가 필요합니다.", EXIT_CODES.INPUT);
      }
      if (!["json", "yaml"].includes(format)) {
        throw new CliError("`--format`은 `json` 또는 `yaml`이어야 합니다.", EXIT_CODES.INPUT);
      }

      const exported = await exportDeployment(client, deploymentId);
      const serialized = format === "yaml"
        ? `${serializeYaml(exported)}\n`
        : `${JSON.stringify(exported, null, 2)}\n`;

      if (outputPath) {
        await fs.writeFile(path.resolve(outputPath), serialized, "utf8");
        if (!globalOptions.quiet) {
          console.log(`내보내기 완료: ${path.resolve(outputPath)}`);
        }
        return;
      }

      if (format === "yaml") {
        printYaml(exported);
      } else {
        printJson(exported);
      }
      return;
    }
    default:
      throw new CliError(
        "`deployments` 하위 명령은 `list`, `get`, `restart`, `scale`, `wait`, `export` 중 하나여야 합니다.",
        EXIT_CODES.INPUT
      );
  }
}

async function handleCost(args, globalOptions, client) {
  const subcommand = args.shift();
  const filePath = takeOption(args, "--file");
  const maxMonthly = takeOption(args, "--max-monthly");
  assertNoUnknownOptions(args);

  if (!filePath) {
    throw new CliError("비용 명령은 `--file <json>`이 필요합니다.", EXIT_CODES.INPUT);
  }

  const payload = JSON.parse(await fs.readFile(path.resolve(filePath), "utf8"));

  switch (subcommand) {
    case "plan": {
      const response = await client.costPlan({
        planned: requirePlannedSpec(payload),
      });
      printCostResponse(response, globalOptions);
      return;
    }
    case "explain": {
      const response = await client.costExplain({
        planned: requirePlannedSpec(payload),
      });
      printCostResponse(response, globalOptions);
      return;
    }
    case "diff": {
      const response = await client.costDiff({
        current: payload.current ?? null,
        planned: requirePlannedSpec(payload),
      });
      printCostResponse(response, globalOptions);
      return;
    }
    case "check": {
      const limit = maxMonthly ? Number(maxMonthly) : Number(payload.monthlyBudgetLimit);
      if (!Number.isFinite(limit) || limit <= 0) {
        throw new CliError("`cost check`에는 `--max-monthly` 또는 파일의 `monthlyBudgetLimit`이 필요합니다.", EXIT_CODES.INPUT);
      }

      const response = await client.costCheck({
        current: payload.current ?? null,
        planned: requirePlannedSpec(payload),
        monthly_budget_limit: limit,
      });
      printCostResponse(response, globalOptions);
      if (response.limit_exceeded) {
        throw new CliError("예산 한도를 초과했습니다.", EXIT_CODES.COST_LIMIT, response);
      }
      return;
    }
    default:
      throw new CliError("`cost` 하위 명령은 `plan`, `diff`, `explain`, `check` 중 하나여야 합니다.", EXIT_CODES.INPUT);
  }
}

function printCostResponse(response, globalOptions) {
  if (globalOptions.json) {
    printJson(response);
    return;
  }

  printKeyValues([
    ["Estimated", formatCurrency(response.estimated_monthly_cost, response.currency)],
    ["Delta", formatCurrency(response.delta_monthly_cost, response.currency)],
    ["Budget", response.monthly_budget_limit ? formatCurrency(response.monthly_budget_limit, response.currency) : "-"],
    ["Exceeded", response.limit_exceeded ? "yes" : "no"],
  ]);

  if (response.cost_breakdown?.length) {
    console.log("\nBreakdown:");
    printRows(response.cost_breakdown, [
      { key: "label", label: "Item" },
      { key: "quantity", label: "Qty" },
      { key: "unit", label: "Unit" },
      { key: (row) => formatCurrency(row.monthly_cost, response.currency), label: "Monthly Cost" },
      { key: "detail", label: "Detail" },
    ]);
  }

  if (response.assumptions?.length) {
    console.log("\nAssumptions:");
    for (const assumption of response.assumptions) {
      console.log(`- ${assumption}`);
    }
  }
}

async function saveProfileTokens(globalOptions, tokens, baseUrl, client) {
  const config = await loadConfig();
  let nextConfig = upsertProfile(config, globalOptions.profile, {
    baseUrl,
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token || null,
  });
  await saveConfig(nextConfig);

  const user = await client.getCurrentUser();
  nextConfig = upsertProfile(nextConfig, globalOptions.profile, {
    baseUrl,
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token || null,
    user,
  });
  await saveConfig(nextConfig);

  if (globalOptions.json) {
    printJson({ profile: globalOptions.profile, user, config_path: getConfigPath() });
    return;
  }

  console.log(`로그인되었습니다: ${user.name} <${user.email}>`);
  console.log(`프로필: ${globalOptions.profile}`);
  console.log(`설정 파일: ${getConfigPath()}`);
}

async function handleDoctor(globalOptions, client) {
  const config = await loadConfig();
  const environment = getEnvironmentProfile();
  const profileInfo = getProfile(config, globalOptions.profile, {
    baseUrl: globalOptions.baseUrl,
  });
  const effectiveProfile = profileInfo.profile;
  const checks = [];
  let highestExitCode = EXIT_CODES.SUCCESS;

  const configPath = getConfigPath();
  const configExists = await fileExists(configPath);
  checks.push({
    key: "config",
    label: "Config file",
    status: configExists ? "ok" : "warn",
    detail: configExists ? configPath : "설정 파일이 아직 없습니다. env var 또는 `auth login`으로 시작할 수 있습니다.",
  });

  checks.push({
    key: "profile",
    label: "Active profile",
    status: "ok",
    detail: profileInfo.name,
  });

  checks.push({
    key: "base_url",
    label: "Base URL",
    status: effectiveProfile.baseUrl ? "ok" : "fail",
    detail: effectiveProfile.baseUrl || DEFAULT_BASE_URL,
    source: globalOptions.baseUrl ? "cli-option" : environment.baseUrl ? "env" : "config",
  });

  checks.push({
    key: "token",
    label: "Access token",
    status: effectiveProfile.accessToken ? "ok" : "warn",
    detail: effectiveProfile.accessToken ? "configured" : "인증이 필요합니다.",
    source: environment.accessToken ? "env" : "config",
  });

  const health = await runCheck("api", "API health", () => client.getSystemHealth());
  checks.push(health.result);
  highestExitCode = Math.max(highestExitCode, health.exitCode);

  const version = await runCheck("version", "Server version", () => client.getSystemVersion());
  checks.push(version.result);
  highestExitCode = Math.max(highestExitCode, version.exitCode);

  if (effectiveProfile.accessToken) {
    const auth = await runCheck("auth", "Authenticated user", () => client.getCurrentUser(), EXIT_CODES.AUTH);
    checks.push(auth.result);
    highestExitCode = Math.max(highestExitCode, auth.exitCode);
  }

  if (globalOptions.json) {
    printJson({
      profile: profileInfo.name,
      config_path: configPath,
      checks,
    });
  } else {
    for (const check of checks) {
      console.log(`[${check.status.toUpperCase()}] ${check.label}: ${check.detail}`);
    }
  }

  if (highestExitCode !== EXIT_CODES.SUCCESS) {
    throw new CliError("CLI 환경 진단 중 실패한 항목이 있습니다.", highestExitCode, { checks });
  }
}

function requirePlannedSpec(payload) {
  if (!payload?.planned) {
    throw new CliError("비용 spec 파일에는 `planned` 객체가 필요합니다.", EXIT_CODES.INPUT);
  }
  return payload.planned;
}

function parseGlobalOptions(argv) {
  const globalOptions = {
    json: false,
    quiet: false,
    help: false,
    profile: "default",
    baseUrl: null,
  };

  const rest = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--json") {
      globalOptions.json = true;
    } else if (arg === "--quiet") {
      globalOptions.quiet = true;
    } else if (arg === "--help" || arg === "-h") {
      globalOptions.help = true;
    } else if (arg === "--profile") {
      globalOptions.profile = argv[index + 1];
      index += 1;
    } else if (arg === "--base-url") {
      globalOptions.baseUrl = argv[index + 1];
      index += 1;
    } else {
      rest.push(arg);
    }
  }

  return { globalOptions, rest };
}

function takeOption(args, name) {
  const index = args.indexOf(name);
  if (index === -1) {
    return null;
  }
  const value = args[index + 1];
  if (!value || value.startsWith("--")) {
    throw new CliError(`${name} 옵션 값이 필요합니다.`, EXIT_CODES.INPUT);
  }
  args.splice(index, 2);
  return value;
}

function takeFlag(args, name) {
  const index = args.indexOf(name);
  if (index === -1) {
    return false;
  }
  args.splice(index, 1);
  return true;
}

function assertNoUnknownOptions(args) {
  const unknown = args.find((arg) => arg.startsWith("--"));
  if (unknown) {
    throw new CliError(`알 수 없는 옵션입니다: ${unknown}`, EXIT_CODES.INPUT);
  }
}

async function askConfirmation(message) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new CliError("TTY 환경이 아니므로 `--yes` 또는 `--no`를 명시해야 합니다.", EXIT_CODES.INPUT);
  }

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  try {
    const answer = await rl.question(`${message} [y/N] `);
    return answer.trim().toLowerCase() === "y";
  } finally {
    rl.close();
  }
}

function truncate(value, maxLength) {
  if (!value) {
    return "";
  }
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, maxLength - 3)}...`;
}

function openBrowser(targetUrl) {
  const command = (() => {
    switch (process.platform) {
      case "darwin":
        return ["open", targetUrl];
      case "win32":
        return ["cmd", "/c", "start", "", targetUrl];
      default:
        return ["xdg-open", targetUrl];
    }
  })();

  const child = spawn(command[0], command.slice(1), {
    detached: true,
    stdio: "ignore",
  });
  child.unref();
}

function printHelp() {
  const homeDir = os.homedir();
  console.log(`KLEPaaS CLI

Usage:
  klepaas [--profile <name>] [--base-url <url>] [--json] <command>

Commands:
  auth login --token <access-token> [--refresh-token <refresh-token>]
  auth login --web
  auth login --code <oauth-code> [--redirect-uri <uri>]
  auth whoami
  auth logout

  ask "<natural language command>"
  confirm <command-log-id> [--yes|--no]
  history [--page <n>] [--size <n>]

  deployments list --repository-id <id> [--page <n>] [--size <n>]
  deployments get <deployment-id>
  deployments restart <deployment-id>
  deployments scale <deployment-id> --replicas <n>
  deployments wait <deployment-id> [--timeout <sec>] [--interval <sec>]
  deployments export <deployment-id> [--format json|yaml] [--output <file>]

  cost plan --file <spec.json>
  cost diff --file <spec.json>
  cost explain --file <spec.json>
  cost check --file <spec.json> [--max-monthly <amount>]

  doctor

Config:
  default base url: ${DEFAULT_BASE_URL}
  config path: ${getConfigPath().replace(homeDir, "~")}
  supported env vars: KLEPAAS_BASE_URL, KLEPAAS_TOKEN, KLEPAAS_REFRESH_TOKEN
`);
}

function resolveBaseUrl(globalOptions) {
  return globalOptions.baseUrl || process.env.KLEPAAS_BASE_URL || DEFAULT_BASE_URL;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runCheck(key, label, fn, exitCodeOnFailure = EXIT_CODES.INPUT) {
  try {
    const data = await fn();
    return {
      exitCode: EXIT_CODES.SUCCESS,
      result: {
        key,
        label,
        status: "ok",
        detail: summarizeCheckData(data),
        data,
      },
    };
  } catch (error) {
    return {
      exitCode: exitCodeOnFailure,
      result: {
        key,
        label,
        status: "fail",
        detail: error.message || String(error),
      },
    };
  }
}

async function fileExists(targetPath) {
  try {
    await fs.access(targetPath);
    return true;
  } catch {
    return false;
  }
}

function summarizeCheckData(data) {
  if (data?.status) {
    return typeof data.status === "string" ? data.status : JSON.stringify(data.status);
  }
  if (data?.version) {
    return data.version;
  }
  if (data?.email) {
    return `${data.name} <${data.email}>`;
  }
  return "ok";
}

async function waitForDeployment(client, deploymentId, { timeoutSeconds, intervalSeconds, json }) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  const timeline = [];
  let lastStatus = null;

  while (Date.now() <= deadline) {
    const statusResponse = await client.getDeploymentStatus(deploymentId);
    timeline.push({
      status: statusResponse.status,
      fail_reason: statusResponse.fail_reason ?? null,
      checked_at: new Date().toISOString(),
    });

    if (statusResponse.status !== lastStatus && !json) {
      console.log(`Deployment ${deploymentId} status: ${statusResponse.status}`);
      if (statusResponse.fail_reason) {
        console.log(`Reason: ${statusResponse.fail_reason}`);
      }
    }
    lastStatus = statusResponse.status;

    if (statusResponse.status === "SUCCESS") {
      if (json) {
        printJson({
          deployment_id: deploymentId,
          final_status: statusResponse.status,
          fail_reason: statusResponse.fail_reason ?? null,
          timeline,
        });
      }
      return;
    }

    if (["FAILED", "CANCELED"].includes(statusResponse.status)) {
      if (json) {
        printJson({
          deployment_id: deploymentId,
          final_status: statusResponse.status,
          fail_reason: statusResponse.fail_reason ?? null,
          timeline,
        });
      }
      throw new CliError(`배포가 ${statusResponse.status} 상태로 종료되었습니다.`, EXIT_CODES.API, statusResponse);
    }

    await sleep(intervalSeconds * 1000);
  }

  throw new CliError("배포 대기 시간이 초과되었습니다.", EXIT_CODES.TIMEOUT, {
    deployment_id: deploymentId,
    last_status: lastStatus,
  });
}

async function exportDeployment(client, deploymentId) {
  const deployment = await client.getDeployment(deploymentId);
  let repository = null;
  let config = null;

  try {
    repository = await findRepositoryById(client, deployment.repository_id);
  } catch {}

  try {
    config = await client.getRepositoryConfig(deployment.repository_id);
  } catch {}

  return {
    apiVersion: "klepaas.io/v1alpha1",
    kind: "DeploymentExport",
    metadata: {
      exportedAt: new Date().toISOString(),
      deploymentId: deployment.id,
      repositoryId: deployment.repository_id,
      repositoryName: deployment.repository_name,
    },
    deployment: {
      branchName: deployment.branch_name,
      commitHash: deployment.commit_hash,
      imageUri: deployment.image_uri,
      status: deployment.status,
      failReason: deployment.fail_reason,
      startedAt: deployment.started_at,
      finishedAt: deployment.finished_at,
      createdAt: deployment.created_at,
    },
    repository: repository
      ? {
          id: repository.id,
          owner: repository.owner,
          repoName: repository.repo_name ?? repository.repoName,
          fullName: `${repository.owner}/${repository.repo_name ?? repository.repoName}`,
          gitUrl: repository.git_url ?? repository.gitUrl,
          cloudVendor: repository.cloud_vendor ?? repository.cloudVendor,
        }
      : null,
    runtime: config
      ? {
          minReplicas: config.min_replicas,
          maxReplicas: config.max_replicas,
          containerPort: config.container_port,
          domainUrl: config.domain_url,
          envVars: config.env_vars ?? {},
        }
      : null,
  };
}

async function findRepositoryById(client, repositoryId) {
  const repositories = await client.getRepositories();
  const repository = (repositories ?? []).find((item) => Number(item.id) === Number(repositoryId));
  if (!repository) {
    throw new CliError(`repository_id=${repositoryId} 저장소를 찾을 수 없습니다.`, EXIT_CODES.API);
  }
  return repository;
}

function serializeYaml(value) {
  const lines = [];
  collectYamlLines(value, 0, lines);
  return lines.join("\n");
}

function collectYamlLines(value, level, lines) {
  const indent = "  ".repeat(level);

  if (Array.isArray(value)) {
    if (value.length === 0) {
      lines.push(`${indent}[]`);
      return;
    }

    for (const item of value) {
      if (isScalar(item)) {
        lines.push(`${indent}- ${yamlScalar(item)}`);
      } else {
        lines.push(`${indent}-`);
        collectYamlLines(item, level + 1, lines);
      }
    }
    return;
  }

  if (value && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) {
      lines.push(`${indent}{}`);
      return;
    }

    for (const [key, nestedValue] of entries) {
      if (isScalar(nestedValue)) {
        lines.push(`${indent}${key}: ${yamlScalar(nestedValue)}`);
      } else {
        lines.push(`${indent}${key}:`);
        collectYamlLines(nestedValue, level + 1, lines);
      }
    }
    return;
  }

  lines.push(`${indent}${yamlScalar(value)}`);
}

function isScalar(value) {
  return value == null || ["string", "number", "boolean"].includes(typeof value);
}

function yamlScalar(value) {
  if (value == null) {
    return "null";
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return /^[A-Za-z0-9_./-]+$/.test(value) ? value : JSON.stringify(value);
}

main().catch((error) => {
  if (error instanceof CliError) {
    console.error(error.message);
    if (process.argv.includes("--json") && error.details) {
      printJson(error.details);
    }
    process.exit(error.exitCode ?? 1);
  }

  console.error(error?.message || String(error));
  process.exit(1);
});
