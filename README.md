# Live Transkription (offline, Android)

Offline-Spracherkennung mit Vosk (Kaldi). Kein Internet nötig, Text wird live angezeigt
und per "Als Datei speichern" als .txt auf dem Smartphone abgelegt (Ordner frei wählbar).

## Einrichtung
1. Android Studio öffnen -> "Open" -> Ordner `LiveTranscribe`.
2. Sprachmodell herunterladen (einmalig, nur zum Bauen nötig): https://alphacephei.com/vosk/models
   - Schnell/klein:  `vosk-model-small-de-0.15` (~45 MB)
   - Bessere Qualität: `vosk-model-de-0.21` (~1,9 GB, nur für starke Geräte)
3. ZIP entpacken und den INHALT (Ordner `am`, `conf`, `graph`, `ivector` ...) nach
   `app/src/main/assets/model-de/` kopieren. Danach muss `app/src/main/assets/model-de/am/final.mdl` existieren.
4. Handy per USB anschließen -> Run. Oder: Build > Build APK(s).

## Anderen Sprachen
Modell für andere Sprache laden und in `model-de` kopieren (Ordnername ist egal).

## Ohne Android Studio (GitHub Actions)
Projekt in ein neues GitHub-Repo hochladen (inkl. Ordner `.github`). Unter "Actions" läuft der Build
automatisch, das Modell wird dort heruntergeladen. Danach unter dem Lauf -> "Artifacts" -> `LiveTranscribe-APK`.
