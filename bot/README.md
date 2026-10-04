# Strength SMP Bot

This is the actual Minecraft bot service for the website.

## Server

- Address: s1strength.mcsh.io
- Port: 12565
- Bot library: Mineflayer 4.39.0

Mineflayer's current upstream package supports the 1.21.11 line and uses Node 22 in its package metadata. citeturn821248search0turn821248search3

## Run it

From this folder:

```bash
npm install
npm start
```

Set the environment variables from `.env.example` in your host's secret/environment settings.

For an offline/cracked server:

```
BOT_HOST=s1strength.mcsh.io
BOT_PORT=12565
BOT_USERNAME=StrengthBot
BOT_AUTH=offline
BOT_REGISTER_PASSWORD=YOUR_PRIVATE_PASSWORD
BOT_REGISTER_COMMAND=/register __BOT_REGISTER_PASSWORD__ __BOT_REGISTER_PASSWORD__
BOT_LOGIN_COMMAND=/login __BOT_REGISTER_PASSWORD__
BOT_API_KEY=YOUR_PRIVATE_API_KEY
CORS_ORIGIN=https://ndmcgamer320-commits.github.io
ALLOWED_COMMANDS=list,time,weather,say,help
AUTO_CONNECT=true
```

The bot never needs you to put the password or API key in GitHub.

For Microsoft-authenticated servers, set `BOT_AUTH=microsoft` and use the Minecraft account identifier in `BOT_USERNAME`. citeturn821248search3turn821248search7

## Website control

Deploy this bot service somewhere that can keep a Node process running.

On the website, press **CONNECT** in the Bot Control panel. The first time, enter the deployed bot service URL and your private `BOT_API_KEY`. They are kept only in the browser session.

The website can then request:

- bot state
- connect
- disconnect
- allowlisted Minecraft commands

Commands are intentionally allowlisted. Add more command names in `ALLOWED_COMMANDS` when configuring your own server.

## Registration GUI

A Minecraft bot cannot complete a server's external hosting-panel GUI before it connects to the Minecraft server. The included registration/login hooks run after the bot spawns, which covers chat-based auth plugins.

If your server uses an in-game inventory GUI for authentication, its exact buttons/slots depend on the plugin, so a plugin-specific GUI handler would need the plugin name and layout.
