# ClaudeBot – Entwicklungsprojekt

Fabric-Mod für Minecraft **1.21.1** (Java 21, Yarn-Mappings), mit der Claude Code als eigener Spieler „Claude“ in Leas Welt mitspielt. Wahrnehmung und Steuerung laufen als Text über `http://127.0.0.1:25599`.

Sprache: Antworte Lea auf Deutsch, kurz, ohne Wiederholungen.

## Stand
- **v0.1 kompiliert** (Loom 1.10.5, Yarn 1.21.1+build.3, Fabric API 0.116.9+1.21.1). Auf Dedicated Server getestet: Spawn, Chat, Laufen, Abbauen, Crafting, Platzieren, Despawn/Spawn mit Inventar.
- Noch nicht mit echtem Client getestet. Offen: Wahrscheinlichste Laufzeit-Risiken: Bot-Spawn (`FakeConnection`, `PlayerManager.onPlayerConnect`), Bewegung (Position wird pro Tick gesetzt, Physik läuft parallel), Respawn nach Tod.
- Mehrere Bots gleichzeitig (headless getestet: 3 Bots, Chat, Flüstern per `/msg`, Schilder). Gehirn: je Bot ein Claude-Code-Prozess über Les Abo (`play/bot`, nur mit Platzhalter getestet).
- Entscheidungen von Le: nur Claude-Modelle über das Abo, Kontext 128k mit Auto-Compact, Koordinaten sichtbar, Kampf gegen Spieler erlaubt, Namen frei.
- Echtes Sichtfeld (`fair_sight=true`): `see`, `nearby`, `state` und die Suche von `mine <block>` nutzen nur Sichtlinie. `map`/`top` schauen noch durch Wände. Reflexe (`reflexes=true`): Gegenwehr gegen Monster, Essen im Leerlauf.
- Nächstes: Motorik über Eingaben (erst nach Client-Test), JSON-Frames, Bildmodus, `map`/`top` fair machen.

## Befehle
- Bauen: `./gradlew build` → `build/libs/claudebot-0.1.0.jar`
- Testclient: `./gradlew runClient` (Welt laden, „Für LAN öffnen“, `/claude spawn`)
- Bot ansprechen: `play/mc state`, `play/mc help`; bestimmter Bot: `CLAUDEBOT_NAME=Sonnet play/mc state`
- Bots als Spieler starten (Claude Code mit Abo): `play/bot start Sonnet --model sonnet --thema "…"`, `play/bot list`, `play/bot stop all`
- Im Spiel: `/claude spawn <name>`, `/claude despawn|stop <name|all>`, `/claude list`, `/claude theme <name|all> <text|->`

## Aufbau (`src/main/java/dev/lea/claudebot/`)
| Datei | Aufgabe |
|---|---|
| `ClaudeBotMod` | Einstieg: Events, `/claude`-Befehle, Konfiguration (`config/claudebot.properties`) |
| `BotRegistry` | Alle Bots: je Name ein `BotController`, Token, Thema, Höchstzahl |
| `BotPlayer` | `ServerPlayerEntity` ohne Client; tickt sich selbst, meldet Tod |
| `FakeConnection` | Verbindung, die alle Pakete verwirft (keine Mixins nötig) |
| `BotController` | Befehlsparser, Aufgaben (`GotoTask`, `MineTask`, `ReachTask`, `AttackTask` …), `Navigator`, Crafting, Behälter |
| `Pathfinder` | A* auf Blockebene |
| `Vision` | Echtes Sichtfeld: Strahlenraster 64×36, Sichtlinie für Wesen, Hören, Gedächtnis gesehener Blöcke (`see`, `see around`) |
| `Describe` | Welt → Text (`state`, `scan`, `map`, `top`) |
| `EventLog` | Ereignisse für Claude (Chat, Schaden, Aufgaben) |
| `HttpApi` | `GET /bots`, `POST /bots/{name}/cmd`, `GET /bots/{name}/events`; `/cmd` und `/events` für den Standard-Bot |

`play/` ist der Ordner zum **Spielen** (eigene `CLAUDE.md`, Skript `mc`, Starter `bot`, Bot-Ordner `play/bots/<Name>/` mit Persona, Notizen, Zielen – nicht im Git). Diese Datei hier gilt fürs **Entwickeln**.

## Regeln
- Keine Mixins hinzufügen, solange es ohne geht.
- Alles Spielrelevante läuft im Server-Thread (`server.submit`); HTTP-Threads warten nur.
- Minecraft-API-Namen nie raten: in den dekompilierten Quellen nachsehen (`./gradlew genSources`).
- Ausgaben an den spielenden Claude auf Deutsch und knapp halten (Token).
