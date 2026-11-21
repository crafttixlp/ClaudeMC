# ClaudeBot – Entwicklungsprojekt

Fabric-Mod für Minecraft **1.21.1** (Java 21, Yarn-Mappings), mit der Claude Code als eigener Spieler „Claude“ in Leas Welt mitspielt. Wahrnehmung und Steuerung laufen als Text über `http://127.0.0.1:25599`.

Sprache: Antworte Lea auf Deutsch, kurz, ohne Wiederholungen.

## Stand
- **v0.1 kompiliert** (Loom 1.10.5, Yarn 1.21.1+build.3, Fabric API 0.116.9+1.21.1). Auf Dedicated Server getestet: Spawn, Chat, Laufen, Abbauen, Crafting, Platzieren, Despawn/Spawn mit Inventar.
- Noch nicht mit echtem Client getestet. Offen: Wahrscheinlichste Laufzeit-Risiken: Bot-Spawn (`FakeConnection`, `PlayerManager.onPlayerConnect`), Bewegung (Position wird pro Tick gesetzt, Physik läuft parallel), Respawn nach Tod.
- Nächstes Ziel danach: `docs/KONZEPT.md` (JSON-Frames, echtes Sichtfeld), Ausbaustufe 1.

## Befehle
- Bauen: `./gradlew build` → `build/libs/claudebot-0.1.0.jar`
- Testclient: `./gradlew runClient` (Welt laden, „Für LAN öffnen“, `/claude spawn`)
- Bot ansprechen: `play/mc state`, `play/mc help`

## Aufbau (`src/main/java/dev/lea/claudebot/`)
| Datei | Aufgabe |
|---|---|
| `ClaudeBotMod` | Einstieg: Events, `/claude`-Befehle, Konfiguration (`config/claudebot.properties`) |
| `BotPlayer` | `ServerPlayerEntity` ohne Client; tickt sich selbst, meldet Tod |
| `FakeConnection` | Verbindung, die alle Pakete verwirft (keine Mixins nötig) |
| `BotController` | Befehlsparser, Aufgaben (`GotoTask`, `MineTask`, `ReachTask`, `AttackTask` …), `Navigator`, Crafting, Behälter |
| `Pathfinder` | A* auf Blockebene |
| `Describe` | Welt → Text (`state`, `scan`, `map`, `top`) |
| `EventLog` | Ereignisse für Claude (Chat, Schaden, Aufgaben) |
| `HttpApi` | `POST /cmd`, `GET /events` |

`play/` ist der Ordner zum **Spielen** (eigene `CLAUDE.md`, Skript `mc`). Diese Datei hier gilt fürs **Entwickeln**.

## Regeln
- Keine Mixins hinzufügen, solange es ohne geht.
- Alles Spielrelevante läuft im Server-Thread (`server.submit`); HTTP-Threads warten nur.
- Minecraft-API-Namen nie raten: in den dekompilierten Quellen nachsehen (`./gradlew genSources`).
- Ausgaben an den spielenden Claude auf Deutsch und knapp halten (Token).
