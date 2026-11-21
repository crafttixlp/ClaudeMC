# ClaudeBot – Claude Code spielt mit dir Minecraft (Fabric 1.21.1)

Die Mod fügt deiner Welt einen zweiten Spieler **Claude** hinzu (ohne zweiten Account). Claude Code steuert ihn über eine lokale Text-Schnittstelle: Es bekommt Position, Umgebung, Inventar, Mobs und deinen Chat als Text und schickt Befehle wie `goto`, `mine`, `craft`, `place`, `attack` oder `say`.

```
Minecraft (Fabric + ClaudeBot)  ⇄  http://127.0.0.1:25599  ⇄  ./mc  ⇄  Claude Code CLI
```

## 1. Bauen

Voraussetzung: Java 21 (`java -version`).

```bash
cd claudebot
./gradlew build
```

Die Mod liegt danach in `build/libs/claudebot-0.1.0.jar`.

## 2. Installieren

1. [Fabric Loader](https://fabricmc.net/use/installer/) für **1.21.1** installieren.
2. In `~/.minecraft/mods/` legen:
   - `claudebot-0.1.0.jar`
   - [Fabric API](https://modrinth.com/mod/fabric-api) für 1.21.1
3. Minecraft mit dem Fabric-Profil starten und eine Welt laden.

## 3. Spielen

1. Im Spiel: **Esc → „Für LAN öffnen“**. Sonst pausiert Einzelspieler, sobald du ins Terminal wechselst, und Claude friert ein. Alternativ `F3 + P` (Pausieren bei Fokusverlust aus).
2. Im Spiel `/claude spawn`, dann erscheint Claude neben dir. (Claude kann sich auch selbst mit `./mc spawn` holen.)
3. Im Terminal:
   ```bash
   cd claudebot/play
   claude
   ```
   Dann z. B. eintippen: **„Lass uns zusammen spielen!“** Claude liest `CLAUDE.md`, begrüßt dich im Spielchat und wartet auf deine Chatnachrichten.
4. Ab da redest du einfach im Minecraft-Chat mit Claude: „Hol bitte Holz“, „Komm zu mir“, „Bau eine Werkbank“, „Hilfe, ein Zombie!“.

Ingame-Befehle: `/claude spawn`, `/claude despawn`, `/claude stop`, `/claude status`.

Du kannst `./mc` auch selbst ausprobieren, z. B. `./mc state`, `./mc top`, `./mc say Hi`.

## Konfiguration

`~/.minecraft/config/claudebot.properties` (wird beim ersten Start angelegt):

```
port=25599
name=Claude
```

## Was Claude kann

Laufen mit Wegsuche (Springen, Fallen, Schwimmen), Spielern folgen, Blöcke abbauen (mit Werkzeugwahl und Einsammeln), platzieren, Türen/Hebel/Betten benutzen, schlafen, Werkbank-Rezepte herstellen, Öfen und Kisten befüllen und leeren, essen, ausrüsten, kämpfen, Items geben, chatten. Inventar und Position werden wie bei einem normalen Spieler gespeichert.

## Grenzen (Version 0.1)

- Die Bewegung ist vereinfacht: Claude gleitet von Block zu Block, kann aber nicht klettern (Leitern), keine Boote/Pferde reiten und nicht sprinten-springen. Geschlossene Türen umgeht er nicht von selbst; er kann sie mit `use` öffnen.
- Beim Abbauen und Bauen prüft die Mod nur die Reichweite (ca. 4,5 Blöcke), nicht die Sichtlinie.
- Herstellen passiert direkt ohne Crafting-Fenster (Werkbank muss in der Nähe stehen).
- Nur lokal (127.0.0.1). Auf einem eigenen Server läuft die Mod serverseitig; `./mc` muss dann auf dem Server-Rechner laufen.
- Claude Code reagiert nicht in Echtzeit: Zwischen deiner Nachricht und Claudes Reaktion vergehen ein paar Sekunden.

## Fehlersuche

- `Keine Verbindung zu Minecraft`: Ist eine Welt geladen? Steht in `logs/latest.log` „ClaudeBot: Schnittstelle läuft“?
- Claude bewegt sich nicht: Ist das Spiel pausiert (Esc-Menü)? → „Für LAN öffnen“.
- Build-Fehler: Bitte die Fehlermeldung von `./gradlew build` schicken. Die Mod konnte beim Erstellen nicht kompiliert werden (siehe unten).

## Hinweis zur Entstehung

Die Quellen wurden gegen die offiziellen Yarn-Mappings für 1.21.1 und den Quellcode der Fabric API geprüft, aber in der Erstellungsumgebung konnte Minecraft nicht heruntergeladen werden. Daher ist der erste `./gradlew build` auch der erste echte Compile-Test.
