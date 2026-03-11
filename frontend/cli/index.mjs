#!/usr/bin/env node

import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import readline from "node:readline/promises";
import { spawn } from "node:child_process";
import { URL } from "node:url";

import { ApiClient, CliError } from "./api.mjs";
import { DEFAULT_BASE_URL, getConfigPath, getProfile, loadConfig, saveConfig, upsertProfile } from "./config.mjs";
import { formatCurrency, printJson, printKeyValues, printRows } from "./output.mjs";

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
    await saveProfileTokens(globalOptions, tokens, globalOptions.baseUrl || DEFAULT_BASE_URL, client);
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
    globalOptions.baseUrl || DEFAULT_BASE_URL,
    client
  );
}

async function loginWithBrowser(globalOptions, client) {
  const server = http.createServer();
  const codePromise = new Promise((resolve, reject) => {
    server.on("request", (req, res) => {
      try {
        const currentUrl = new URL(req.url, "http://127.0.0.1");
        const code = currentUrl.searchParams.get("code");
        const error = currentUrl.searchParams.get("error");

        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        if (code) {
          res.end("<html><body><h2>KLEPaaS CLI 로그인 완료</h2><p>이 창을 닫아도 됩니다.</p></body></html>");
          resolve(code);
        } else {
          res.end("<html><body><h2>KLEPaaS CLI 로그인 실패</h2><p>터미널로 돌아가 오류를 확인하세요.</p></body></html>");
          reject(new CliError(error || "OAuth 콜백에서 code를 받지 못했습니다.", EXIT_CODES.AUTH));
        }
      } catch (requestError) {
        reject(requestError);
      }
    });
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const redirectUri = `http://127.0.0.1:${address.port}/callback`;
  const oauthResponse = await client.getOAuthUrl("github", redirectUri);
  const authUrl = oauthResponse.url;

  console.log(`브라우저에서 인증을 시작합니다: ${authUrl}`);
  openBrowser(authUrl);

  const timeoutPromise = new Promise((_, reject) =>
    setTimeout(() => reject(new CliError("OAuth 인증 시간이 초과되었습니다.", EXIT_CODES.TIMEOUT)), 120000)
  );

  try {
    const oauthCode = await Promise.race([codePromise, timeoutPromise]);
    const tokens = await client.exchangeOAuthCode(oauthCode, redirectUri);
    await saveProfileTokens(globalOptions, tokens, globalOptions.baseUrl || DEFAULT_BASE_URL, client);
  } finally {
    server.close();
  }
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
    default:
      throw new CliError("`deployments` 하위 명령은 `list`, `get`, `restart`, `scale` 중 하나여야 합니다.", EXIT_CODES.INPUT);
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

  cost plan --file <spec.json>
  cost diff --file <spec.json>
  cost explain --file <spec.json>
  cost check --file <spec.json> [--max-monthly <amount>]

Config:
  default base url: ${DEFAULT_BASE_URL}
  config path: ${getConfigPath().replace(homeDir, "~")}
`);
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
