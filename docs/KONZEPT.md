# Wahrnehmungskonzept: Minecraft als Text sehen (Ziel für Version 0.2+)

Claude bekommt dieselben Informationen wie ein Mensch am Bildschirm, als JSON-Frame pro Blick plus Ereignisstrom.

## Grundsätze
1. Blick statt Datenbank: nur was von Claudes Augen aus sichtbar ist (v0.1 scannt durch Wände – entfällt).
2. Bedeutung statt Pixel: Blöcke werden zu Flächen und Objekten zusammengefasst.
3. Detail nach Entfernung: nah Einzelblöcke, mittel Flächen, fern Landmarken.
4. Änderungen statt Wiederholung: nach vollem Frame nur Deltas und Ereignisse.
5. Alles anfassbar: jede Angabe trägt Koordinaten oder ID für Befehle.

## Kanäle → Frame-Teile
| Mensch | Frame | Inhalt |
|---|---|---|
| 3D-Bild | `view` | Flächen, Objekte, Wesen im Sichtfeld |
| Fadenkreuz | `crosshair` | Block/Wesen in Blickmitte, Seite, Reichweite |
| HUD | `self` | Leben, Hunger, Luft, XP, Hotbar, Hand, Effekte, Aufgabe |
| F3 | `self.pos/facing`, `world` | Position, Dimension, Biom, Zeit, Wetter |
| Ton (Untertitel) | `sounds` | Geräusch, Richtung, grobe Entfernung |
| Chat | `events` | Chat, Systemmeldungen |
| Fenster | `screen` | Slots, Fortschritt, Angebote |
| Erinnerung | `memory` (Abfrage) | Orte, gesehene Erze, Kisteninhalte |

## Frame-Beispiel
```json
{
  "frame": 1842, "tick": 512340, "kind": "full", "detail": "look",
  "self": {"pos": [104.5, 67.0, -220.3], "yaw": 180, "pitch": 5, "facing": "north",
    "health": 17, "food": 14, "air": 300, "xp_level": 4, "on_ground": true, "light": 11,
    "held": {"item": "stone_pickaxe", "durability": [98, 131]},
    "hotbar": ["stone_pickaxe", "oak_log*12", "bread*3", null, "torch*20"],
    "task": {"name": "mine iron_ore", "progress": "1/3"}},
  "world": {"dimension": "overworld", "biome": "forest", "time": "dusk", "night_in_s": 45, "weather": "clear"},
  "crosshair": {"type": "block", "block": "iron_ore", "pos": [104, 66, -224], "face": "south", "dist": 3.6, "reachable": true},
  "view": {"fov": 90, "range": 48,
    "surfaces": [
      {"id": "s1", "what": "grass_block", "shape": "floor", "box": [[96, 66, -240], [112, 66, -221]]},
      {"id": "s2", "what": "stone", "shape": "wall", "box": [[100, 66, -226], [108, 70, -225]], "dist": 4,
       "contains": {"iron_ore": [[104, 66, -224], [105, 67, -224]]}}],
    "objects": [
      {"id": "o1", "what": "oak_tree", "base": [110, 67, -230], "height": 6, "dir": "front-right", "dist": 11},
      {"id": "o2", "what": "cave_opening", "pos": [98, 64, -232], "dir": "front-left", "dist": 13, "dark": true}],
    "entities": [
      {"id": "e417", "type": "player", "name": "Lea", "pos": [106.2, 67, -222.9], "dir": "right", "dist": 3.1,
       "doing": "mining", "held": "iron_pickaxe", "looking_at": [106, 67, -225]},
      {"id": "e502", "type": "zombie", "hostile": true, "pos": [99, 64, -233], "dir": "front-left", "dist": 14, "moving": "towards_you"}]},
  "sounds": [{"what": "Zombie stöhnt", "dir": "front-left", "dist": "~14", "age_ticks": 12}],
  "events": [{"t": 512331, "type": "chat", "from": "Lea", "text": "Pass auf, da kommt ein Zombie!"}],
  "alerts": ["hostile approaching: e502", "night in 45 s"]
}
```

## Sehen (Vision)
- Strahlenraster ca. 96×54 über 90°×60°, Reichweite 48–64 Blöcke; erster undurchsichtiger Block beendet den Strahl. Glas/Wasser/Blätter notieren, gedämpft weiter.
- Getroffene Blockseiten gleichen Typs in einer Ebene → Rechtecke mit Rolle `floor|wall|ceiling|slope`.
- Objektregeln (Start mit 10): Baum, Erzader, Wasser, Lava, Höhle, Bauwerk, Feld, Kiste, Bett, Tür.
- Licht: unter Lichtwert 4 nur `unknown_dark`, max. 8 Blöcke weit.
- `crosshair`: eigener exakter Strahl mit getroffener Seite.
- Außerhalb des Sichtfelds nur hören; `look` dreht, `panorama` = 4 Frames.
- Spieler liefern `looking_at` (Zeigen: „bau das da ab“).

## Detailstufen im Raum
| Stufe | Entfernung | Darstellung |
|---|---|---|
| Nah | 0–6 | Schichtgitter mit Palette, `?` = nicht einsehbar |
| Mittel | 6–24 | Flächen und Objekte mit Eckkoordinaten |
| Fern | 24+ | Landmarken (Richtung, Entfernung), Horizonthöhe in 16 Richtungen |

```json
"near": {"origin": [101, 65, -224], "size": [7, 3, 4],
  "palette": {".": "air", "g": "grass_block", "s": "stone", "i": "iron_ore", "@": "you", "?": "not_visible"},
  "layers": {"67": ["sss.sss", "...@...", ".......", "......."],
             "66": ["ssisiss", "???????", "???????", "???????"]}}
```

## Wesen
Nur mit Sichtlinie. Felder: `id` (stabil), `type`, `name`, `pos`, `dir`, `dist`, `moving`, `doing`, `held`, `armor`, `looking_at`, `state` (`hurt`, `burning`, `fuse_lit`, `baby`, `tamed`), `hostile`. Keine exakten Lebenspunkte von Mobs.

## Körper und Hören
`self` plus `in_water`, `burning`, `falling`, `sneaking`, `hit_from`. Hören wie Untertitel: Text, Richtung, grobe Entfernung, auch durch Wände. Technik: Pakete an den Bot in `FakeConnection.send` mitlesen (Ton, Chat, Schaden) statt verwerfen.

## Fenster (`screen`)
Echte Fenster öffnen (statt Direktzugriff). Typ, Titel, Slots, Fortschritt (Ofen), Kosten (Amboss, Zaubertisch), Handelsliste. Befehle: `click <slot>`, `move <slot> <ziel> [n]`, `craft <rezept> [n]`, `close`.

## Ereignisse und Deltas
```json
{"frame": 1843, "kind": "delta", "since": 1842,
 "self": {"health": 14, "hit_from": "front-left"},
 "entities": {"moved": [{"id": "e502", "pos": [102, 66, -223], "dist": 3.4}], "gone": [], "new": []},
 "blocks": [{"pos": [104, 66, -224], "was": "iron_ore", "now": "air"}],
 "events": [{"type": "damage", "amount": 3, "by": "e502"}]}
```
| Klasse | Beispiele | Wirkung |
|---|---|---|
| critical | Schaden, Tod, Feind < 8 Blöcke, Ertrinken, Brennen | weckt `wait`, unterbricht Aufgaben |
| social | Chat, Zeigen, Item-Übergabe, Join/Leave | weckt `wait` |
| task | fertig, gescheitert, steckt fest | weckt `wait` |
| world | Nacht, Regen, Explosion, Blockänderung | gesammelt im nächsten Frame |

Abos: `subscribe: ["critical", "social"]`.

## Gedächtnis (JSON im Weltordner, nur Gesehenes, mit Alter)
`places`, `seen` (pro Chunk Höhe + bemerkenswerte Blöcke), `containers`, `log`, `notes`, `goals`. Abfragen: `recall <block|place|where item>`, `note`, `goals`, `briefing` (~300 Token, Start jeder Sitzung).

## Aktionen
| Stufe | Beispiele |
|---|---|
| 1 Eingaben | `look`, `move forward 10`, `jump`, `left_click hold`, `right_click`, `hotbar 3` |
| 2 Handlungen (v0.1) | `goto`, `mine`, `place`, `use`, `attack`, `craft`, `eat` |
| 3 Fähigkeiten | `gather oak_log 16`, `build shelter`, `guard Lea`, `smelt all raw_iron`, `explore north 200` |

```json
{"id": 77, "do": "mine", "target": {"block": "iron_ore", "count": 3}, "interrupt_on": ["critical", "chat"], "timeout_s": 60}
{"id": 77, "status": "interrupted", "reason": "damage by e502", "result": {"mined": 1}, "frame": {"kind": "delta", "self": {"health": 14}}}
```
Status: `done|failed|interrupted|running`. Reflexe (einschaltbar, als Ereignis gemeldet): fliehen bei wenig Leben, zurückschlagen, essen unter Hunger 6, raus aus Lava/Feuer, Abstand zu Creepern.

## Detailstufen der Frames (Tokenwerte geschätzt, messen!)
`glance` ~150 (self, crosshair, alerts, Ereignisse) · `look` ~600 (+ Objekte, Wesen, Landmarken) · `study` ~2000 (+ Nahfeld, alle Flächen) · `panorama` ~2000. Parameter: `focus` (`ores|entities|structures|danger`), `range`, `since`.

## Fairness
Kein Röntgenblick, Dunkelheit verbirgt, keine fremden Inventare/Lebenspunkte, Hören ohne Koordinaten, Gedächtnis nur Gesehenes, Koordinaten erlaubt (`coords: false` möglich). Testmodus `omniscient`, wird im Chat angekündigt.

## Bausteine
| Baustein | Aufgabe | zu v0.1 |
|---|---|---|
| `Vision` | Strahlen, Blockseiten, Fadenkreuz | neu, ersetzt `scan/map/top` |
| `SceneBuilder` | Flächen, Objekte, Landmarken, Nahfeld | neu |
| `EntityView` | Wesen mit Sichtlinie, Handlung, Blickziel | erweitert `Describe.nearby` |
| `PacketTap` | Ton/Chat/Treffer aus Paketen | erweitert `FakeConnection` |
| `ScreenView` | echte Fenster | ersetzt Kistenzugriff |
| `EventBus` | typisierte Ereignisse, Klassen, Abos | erweitert `EventLog` |
| `Memory` | JSON-Gedächtnis, `recall`, `briefing` | neu |
| `Reflexes`, `Skills` | Sofortreaktionen, Stufe-3-Fähigkeiten | neu / auf Tasks |
| Schnittstelle | `/frame`, `/act`, `/events`, später MCP | erweitert `/cmd` |

## Ausbaustufen
1. JSON-Frames `glance`/`look` (self, crosshair, Wesen mit Sichtlinie, typisierte Ereignisse) – fertig, wenn Claude damit so gut spielt wie mit Text.
2. `Vision`, Nahfeld, Flächen; Röntgen-Scans entfernt – Erz hinter der Wand taucht nie auf.
3. Objekte, Landmarken, `Memory`, `briefing` – neue Sitzung findet nach Hause.
4. `PacketTap`, `ScreenView` – Creeper hinter sich bemerken, Ofen über Fenster bedienen.
5. `Skills`, `Reflexes`, MCP-Server – „Bau uns eine Hütte“ mit < 10 Befehlen.

## Offene Fragen
Tokenkosten messen · Objektregeln (Fehlerkennung schlimmer als Lücke) · Rechenlast des Rasters · Autonomie der Reflexe · Koordinaten ja/nein · Fenster für Händler/Zaubertisch · Nether/Ende.
