# Start-Prompt für das neue Projekt

Zip entpacken, im Ordner `claudebot` Claude Code starten (`claude`) und das hier einfügen:

```
Lies CLAUDE.md, README.md und docs/KONZEPT.md. Das ist eine Fabric-Mod für Minecraft 1.21.1, mit der du als eigener Spieler mit mir spielst. Version 0.1 wurde noch nie kompiliert.

1. Führe ./gradlew build aus und behebe alle Compile-Fehler, bis der Build durchläuft. Rate keine Minecraft-Namen – schau mit ./gradlew genSources in die Quellen.
2. Sag mir dann, wie ich die Mod teste (jar-Pfad, /claude spawn, play/mc state), und warte auf meine Rückmeldung aus dem Spiel.
3. Behebe, was im Spiel nicht funktioniert (Spawn, Laufen, Abbauen, Chat).
4. Wenn v0.1 läuft: Setze Ausbaustufe 1 aus docs/KONZEPT.md um (JSON-Frames glance/look über /frame, typisierte Ereignisse) und frag mich vorher, falls etwas unklar ist.

Antworte auf Deutsch und kurz.
```

Zum Spielen (wenn die Mod läuft): `cd play && claude`, dann „Lass uns zusammen spielen!“
