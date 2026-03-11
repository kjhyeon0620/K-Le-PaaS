import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export const DEFAULT_BASE_URL = process.env.KLEPAAS_BASE_URL || "http://localhost:8080";
const CONFIG_DIR = path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "klepaas");
const CONFIG_PATH = path.join(CONFIG_DIR, "config.json");

export async function loadConfig() {
  try {
    const raw = await fs.readFile(CONFIG_PATH, "utf8");
    return JSON.parse(raw);
  } catch (error) {
    if (error && error.code === "ENOENT") {
      return defaultConfig();
    }
    throw error;
  }
}

export async function saveConfig(config) {
  await fs.mkdir(CONFIG_DIR, { recursive: true });
  await fs.chmod(CONFIG_DIR, 0o700).catch(() => {});
  await fs.writeFile(CONFIG_PATH, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  await fs.chmod(CONFIG_PATH, 0o600).catch(() => {});
}

export function getConfigPath() {
  return CONFIG_PATH;
}

export function getProfile(config, profileName = "default") {
  const effectiveConfig = config ?? defaultConfig();
  const profiles = effectiveConfig.profiles ?? {};
  const name = profileName || effectiveConfig.activeProfile || "default";
  return {
    name,
    profile: profiles[name] ?? { baseUrl: DEFAULT_BASE_URL },
  };
}

export function upsertProfile(config, profileName, patch) {
  const effectiveConfig = config ?? defaultConfig();
  const name = profileName || effectiveConfig.activeProfile || "default";
  const current = effectiveConfig.profiles?.[name] ?? { baseUrl: DEFAULT_BASE_URL };
  return {
    ...effectiveConfig,
    activeProfile: name,
    profiles: {
      ...(effectiveConfig.profiles ?? {}),
      [name]: {
        ...current,
        ...patch,
      },
    },
  };
}

function defaultConfig() {
  return {
    version: 1,
    activeProfile: "default",
    profiles: {
      default: {
        baseUrl: DEFAULT_BASE_URL,
      },
    },
  };
}
