const mineflayer = require("mineflayer");
const express = require("express");
const nbt = require("prismarine-nbt");

const HOST = process.env.BOT_HOST || "s1strength.mcsh.io";
const PORT = Number(process.env.BOT_PORT || 12565);
const USERNAME = process.env.BOT_USERNAME || "StrengthBot";
const AUTH = (process.env.BOT_AUTH || "offline").toLowerCase();
const API_KEY = process.env.BOT_API_KEY || "";
const HTTP_PORT = Number(process.env.PORT || 3000);
const CORS_ORIGIN = process.env.CORS_ORIGIN || "https://ndmcgamer320-commits.github.io";
const AUTO_CONNECT = (process.env.AUTO_CONNECT || "true").toLowerCase() !== "false";

const PREJOIN_PASSWORD = process.env.PREJOIN_PASSWORD || "";
const PREJOIN_EMAIL = process.env.PREJOIN_EMAIL || "";
const PREJOIN_REGISTER = (process.env.PREJOIN_REGISTER || "true").toLowerCase() === "true";
const PREJOIN_REGISTER_ACTION = process.env.PREJOIN_REGISTER_ACTION || "nlogin:register/yes";
const PREJOIN_LOGIN_ACTION = process.env.PREJOIN_LOGIN_ACTION || "nlogin:login/yes";
const PREJOIN_PASSWORD_FIELD = process.env.PREJOIN_PASSWORD_FIELD || "password";
const PREJOIN_CONFIRM_FIELD = process.env.PREJOIN_CONFIRM_FIELD || "confirm_password";
const PREJOIN_EMAIL_FIELD = process.env.PREJOIN_EMAIL_FIELD || "email";

const ALLOWED_COMMANDS = new Set(
  (process.env.ALLOWED_COMMANDS || "list,time,weather,say,help")
    .split(",")
    .map(value => value.trim().toLowerCase())
    .filter(Boolean)
);

let bot = null;
let connecting = false;
let lastError = "";
let lastEvent = "Starting bot service";
let reconnectTimer = null;
let reconnectDelay = 5000;
let authStage = "waiting";

function logEvent(message) {
  lastEvent = message;
  console.log(new Date().toISOString(), message);
}

function prepareCommand(command) {
  let value = String(command || "").trim();

  if (!value) throw new Error("Command is empty");
  if (value.length > 120) throw new Error("Command is too long");

  value = value.replace(/^\/+/, "");
  const name = value.split(/\s+/)[0].toLowerCase();

  if (!ALLOWED_COMMANDS.has(name)) {
    throw new Error("Command not allowed: /" + name);
  }

  return "/" + value;
}

function findStrings(value, output = []) {
  if (typeof value === "string") {
    output.push(value);
    return output;
  }

  if (Array.isArray(value)) {
    for (const item of value) findStrings(item, output);
    return output;
  }

  if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      output.push(String(key));
      findStrings(item, output);
    }
  }

  return output;
}

function collectActionIds(value, output = []) {
  if (!value || typeof value !== "object") return output;

  if (typeof value.id === "string") output.push(value.id);
  if (typeof value.action === "string") output.push(value.action);

  if (Array.isArray(value)) {
    for (const item of value) collectActionIds(item, output);
  } else {
    for (const item of Object.values(value)) collectActionIds(item, output);
  }

  return output;
}

function actionPayload(fields) {
  const payload = {};

  if (PREJOIN_PASSWORD) {
    payload[PREJOIN_PASSWORD_FIELD] = PREJOIN_PASSWORD;
    payload[PREJOIN_CONFIRM_FIELD] = PREJOIN_PASSWORD;
  }

  if (PREJOIN_EMAIL && PREJOIN_EMAIL_FIELD) {
    payload[PREJOIN_EMAIL_FIELD] = PREJOIN_EMAIL;
  }

  return payload;
}

function sendCustomClick(actionId, fields = {}) {
  if (!bot || !bot._client) throw new Error("Bot client is not connected");

  const payload = actionPayload(fields);
  const tag = nbt.comp(
    Object.fromEntries(
      Object.entries(payload).map(([key, value]) => [key, nbt.string(String(value))])
    )
  );

  bot._client.write("custom_click_action", {
    id: actionId,
    nbt: tag
  });

  logEvent("GUI action sent: " + actionId);
}

function handlePreJoinDialog(data) {
  const dialog = data && data.dialog ? data.dialog : data;
  const strings = findStrings(dialog).join(" ").toLowerCase();
  const ids = [...new Set(collectActionIds(dialog))];

  const registerLike = strings.includes("register") || strings.includes("registr");
  const loginLike = strings.includes("login") || strings.includes("log in");

  const matchingRegister = ids.find(id =>
    /register/i.test(id) && /(yes|submit|confirm|accept)/i.test(id)
  );
  const matchingLogin = ids.find(id =>
    /login/i.test(id) && /(yes|submit|confirm|accept)/i.test(id)
  );

  logEvent(
    "Pre-join dialog received: " +
    (registerLike ? "REGISTER" : loginLike ? "LOGIN" : "UNKNOWN") +
    " | actions=" + (ids.slice(0, 8).join(",") || "none")
  );

  if (!PREJOIN_PASSWORD) {
    lastError = "PREJOIN_PASSWORD is not configured";
    authStage = "needs-password";
    return;
  }

  const actionId =
    registerLike && PREJOIN_REGISTER
      ? (matchingRegister || PREJOIN_REGISTER_ACTION)
      : (matchingLogin || PREJOIN_LOGIN_ACTION);

  authStage = registerLike && PREJOIN_REGISTER ? "registering" : "logging-in";

  // The custom_click_action payload is an optional length-prefixed NBT compound.
  // Current minecraft-protocol supports the 1.21.11 packet definitions.
  try {
    sendCustomClick(actionId);
    logEvent("Submitted pre-join " + authStage + " GUI");
  } catch (error) {
    lastError = error && error.message ? error.message : String(error);
    logEvent("Pre-join GUI error: " + lastError);
  }
}

function scheduleReconnect() {
  if (reconnectTimer || !AUTO_CONNECT) return;

  const delay = reconnectDelay;
  logEvent("Reconnecting in " + Math.round(delay / 1000) + "s");

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);

  reconnectDelay = Math.min(reconnectDelay * 2, 60000);
}

function connect() {
  if (bot || connecting) {
    return { connected: Boolean(bot), connecting };
  }

  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  reconnectDelay = 5000;
  authStage = "connecting";
  connecting = true;
  lastError = "";

  logEvent("Connecting to " + HOST + ":" + PORT);

  bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: USERNAME,
    auth: AUTH,
    version: false,
    hideErrors: false,
    checkTimeoutInterval: 30000
  });

  // The register/login dialog may arrive in the CONFIGURATION state,
  // before the normal player spawn event.
  bot._client.on("packet", (data, meta) => {
    if (meta && meta.name === "show_dialog") {
      handlePreJoinDialog(data);
    }
  });

  bot.once("login", () => {
    authStage = "logged-in";
    logEvent("Bot login phase complete");
  });

  bot.once("spawn", () => {
    connecting = false;
    reconnectDelay = 5000;
    authStage = "spawned";
    logEvent("Bot spawned successfully");
  });

  bot.on("messagestr", message => {
    const clean = String(message || "").replace(/§./g, "");
    if (clean) logEvent("MC: " + clean.slice(0, 180));
  });

  bot.on("kicked", reason => {
    lastError = typeof reason === "string" ? reason : JSON.stringify(reason);
    logEvent("Bot kicked");
    bot = null;
    connecting = false;
    scheduleReconnect();
  });

  bot.on("end", () => {
    logEvent("Bot disconnected");
    bot = null;
    connecting = false;
    scheduleReconnect();
  });

  bot.on("error", error => {
    lastError = error && error.message ? error.message : String(error);
    logEvent("Bot error: " + lastError);
    connecting = false;
    if (!bot) scheduleReconnect();
  });

  return { connected: false, connecting: true };
}

function disconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  if (bot) {
    logEvent("Disconnect requested");
    bot.quit("Website requested disconnect");
    bot = null;
  }

  connecting = false;
  authStage = "disconnected";
  return { connected: false, connecting: false };
}

const app = express();
app.use(express.json({ limit: "16kb" }));

app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", CORS_ORIGIN);
  res.header("Access-Control-Allow-Headers", "Content-Type,X-API-Key");
  res.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");

  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

function requireKey(req, res, next) {
  if (!API_KEY) {
    return res.status(503).json({ error: "BOT_API_KEY is not configured" });
  }

  if (req.get("X-API-Key") !== API_KEY) {
    return res.status(401).json({ error: "Invalid bot API key" });
  }

  next();
}

app.get("/health", (req, res) => {
  res.json({ ok: true, service: "strength-smp-bot" });
});

app.get("/state", requireKey, (req, res) => {
  res.json({
    connected: Boolean(bot),
    connecting,
    authStage,
    username: bot && bot.username ? bot.username : USERNAME,
    host: HOST,
    port: PORT,
    lastEvent,
    lastError
  });
});

app.post("/connect", requireKey, (req, res) => {
  const result = connect();

  res.json({
    ...result,
    message: result.connecting ? "Connection requested" : "Bot is already connected"
  });
});

app.post("/disconnect", requireKey, (req, res) => {
  disconnect();
  res.json({ connected: false, message: "Bot disconnected" });
});

app.post("/command", requireKey, (req, res) => {
  if (!bot) {
    return res.status(409).json({ error: "Bot is not connected" });
  }

  try {
    const command = prepareCommand(req.body && req.body.command);
    bot.chat(command);
    logEvent("CMD: " + command);
    res.json({ ok: true, message: "Command sent", output: command });
  } catch (error) {
    res.status(400).json({
      error: error && error.message ? error.message : "Invalid command"
    });
  }
});

app.listen(HTTP_PORT, "0.0.0.0", () => {
  logEvent("Bot API listening on port " + HTTP_PORT);
  if (AUTO_CONNECT) connect();
});
