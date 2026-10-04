const mineflayer = require("mineflayer");
const express = require("express");

const HOST = process.env.BOT_HOST || "s1strength.mcsh.io";
const PORT = Number(process.env.BOT_PORT || 12565);
const USERNAME = process.env.BOT_USERNAME || "StrengthBot";
const AUTH = (process.env.BOT_AUTH || "offline").toLowerCase();
const API_KEY = process.env.BOT_API_KEY || "";
const HTTP_PORT = Number(process.env.PORT || 3000);
const CORS_ORIGIN = process.env.CORS_ORIGIN || "https://ndmcgamer320-commits.github.io";
const AUTO_CONNECT = (process.env.AUTO_CONNECT || "true").toLowerCase() !== "false";

const REGISTER_PASSWORD = process.env.BOT_REGISTER_PASSWORD || "";
const REGISTER_COMMAND = process.env.BOT_REGISTER_COMMAND || "";
const LOGIN_COMMAND = process.env.BOT_LOGIN_COMMAND || "";

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

function logEvent(message) {
  lastEvent = message;
  console.log(new Date().toISOString(), message);
}

function renderCommand(command) {
  return String(command || "")
    .replaceAll("__BOT_REGISTER_PASSWORD__", REGISTER_PASSWORD)
    .trim();
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

function sendStartupCommand(command) {
  const rendered = renderCommand(command);
  if (!bot || !rendered) return;

  logEvent("Startup: " + rendered.split(" ")[0]);
  bot.chat(rendered);
}

function connect() {
  if (bot || connecting) {
    return { connected: Boolean(bot), connecting };
  }

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

  bot.once("login", () => {
    logEvent("Bot logged in");
  });

  bot.once("spawn", () => {
    connecting = false;
    logEvent("Bot spawned");

    setTimeout(() => {
      sendStartupCommand(REGISTER_COMMAND);
      setTimeout(() => sendStartupCommand(LOGIN_COMMAND), 3000);
    }, 1500);
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
  });

  bot.on("end", () => {
    logEvent("Bot disconnected");
    bot = null;
    connecting = false;
  });

  bot.on("error", error => {
    lastError = error && error.message ? error.message : String(error);
    logEvent("Bot error: " + lastError);
    connecting = false;
  });

  return { connected: false, connecting: true };
}

function disconnect() {
  if (bot) {
    logEvent("Disconnect requested");
    bot.quit("Website requested disconnect");
    bot = null;
  }

  connecting = false;
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

app.listen(HTTP_PORT, () => {
  logEvent("Bot API listening on port " + HTTP_PORT);
  if (AUTO_CONNECT) connect();
});
