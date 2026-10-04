# Strength SMP Bot

The bot connects to **s1strength.mcsh.io:12565** and handles the server's graphical authentication flow.

## Pre-join registration GUI

The server can show its register/login dialog during Minecraft's **configuration phase**, before the player fully joins. AuthMe documents this as its Paper/Folia pre-join dialog flow, and Minecraft 1.21.11 defines the `show_dialog` and `custom_click_action` packets for this stage. citeturn599692search0turn878506search4

This version of the bot does **not** send `/register` or `/login` on spawn.

Instead it:

1. Watches for the `show_dialog` packet.
2. Detects whether the dialog looks like registration or login.
3. Finds a matching register/login action ID when one is present.
4. Sends a `custom_click_action` packet with the private password payload.
5. Continues into the normal connection if the server accepts the dialog.

The protocol supports `custom_click_action` in the configuration state, and recent minecraft-protocol versions include the 1.21.11 packet definitions. citeturn824410search0turn878506search4turn151651search0

### Configuration

Set these only in your bot host's environment/secret settings:

```
BOT_HOST=s1strength.mcsh.io
BOT_PORT=12565
BOT_USERNAME=StrengthBot
BOT_AUTH=offline

PREJOIN_PASSWORD=YOUR_PRIVATE_BOT_PASSWORD
PREJOIN_EMAIL=
PREJOIN_REGISTER=true

PREJOIN_REGISTER_ACTION=nlogin:register/yes
PREJOIN_LOGIN_ACTION=nlogin:login/yes

PREJOIN_PASSWORD_FIELD=password
PREJOIN_CONFIRM_FIELD=confirm_password
PREJOIN_EMAIL_FIELD=email

BOT_API_KEY=YOUR_PRIVATE_API_KEY
CORS_ORIGIN=https://ndmcgamer320-commits.github.io
ALLOWED_COMMANDS=list,time,weather,say,help
AUTO_CONNECT=true
```

Do not paste the real password or API key into GitHub or into this chat.

### Why the previous version failed

The old bot tried to send chat commands after spawn. Your server is using a graphical pre-join authentication flow, so those commands are the wrong mechanism. AuthMe's current documentation confirms that this kind of registration dialog can appear before the player fully joins. citeturn599692search2turn603403search0

The bot now watches the configuration-state dialog instead.

### nLogin note

The protocol issue that documents the 1.21.6+ custom-click flow shows the `nlogin:login/yes` action and an NBT payload field named `password`. citeturn753084search0

Because nLogin is proprietary, the exact register payload can vary by its current implementation. The bot therefore lets you set the action ID and payload field names through environment variables without putting credentials in the repository.

### Run

```bash
npm install
npm start
```

For a host that uses the included Render Blueprint, put the secrets in the service environment settings and deploy the `bot` directory.
