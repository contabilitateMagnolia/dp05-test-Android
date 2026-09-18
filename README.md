# DP-05 Test — instrucțiuni

Aplicație Android minimală pentru testarea emiterii de bonuri fiscale (card/numerar,
0.01 lei) și a rapoartelor X/Z pe o casă de marcat Datecs DP-05, conectată prin
cablu USB direct la telefon (folosește API-ul nativ Android USB Host, nu WebUSB).

## Cum obții fișierul .apk (fără să instalezi nimic pe calculator)

1. Creează un cont gratuit pe [github.com](https://github.com) dacă nu ai deja.
2. Creează un repository nou, **privat** (Settings → vizibilitate Private), de exemplu `dp05-test`.
3. Urcă TOATE fișierele și folderele din acest proiect exact cu aceeași structură
   (inclusiv folderul ascuns `.github/`) — cel mai simplu e prin "Add file" → "Upload files"
   în interfața web GitHub, trăgând tot conținutul folderului.
4. După ce ai urcat fișierele, mergi la tab-ul **Actions** din repository.
5. Ar trebui să vezi automat un workflow numit "Build APK" care a pornit (sau apasă
   "Run workflow" dacă nu a pornit singur).
6. Așteaptă 3-5 minute până se termină (bulina verde ✅).
7. Intră în acel build → derulează jos la secțiunea **Artifacts** → descarcă
   `dp05-debug-apk` (un fișier .zip care conține `.apk`-ul).
8. Dezarhivează, transferă `.apk`-ul pe telefon (Google Drive, email, cablu — orice metodă).

## Cum instalezi pe telefon

1. Pe S24: Setări → Aplicații → apasă pe cele trei puncte → "Instalare aplicații necunoscute"
   → permite pentru aplicația prin care ai deschis fișierul (Chrome, Fișiere, etc.).
2. Deschide fișierul `.apk` de pe telefon și apasă "Instalează".
3. Poate apărea un avertisment Play Protect ("aplicație nesigură") — e normal pentru
   orice aplicație din afara Play Store; apasă "Instalează oricum".

## Cum se folosește

1. Conectează casa de marcat la telefon (adaptor OTG USB-C → USB-A → cablul original
   al casei de marcat).
2. Deschide aplicația, apasă **"Conectare USB"**.
3. Va apărea un dialog Android care cere permisiune de acces la dispozitiv — acceptă.
4. Dacă status-ul devine "✅ Conectat la DP-05", butoanele de test se activează.
5. **"Test 0.01 CARD"** / **"Test 0.01 NUMERAR"** — trimit un bon complet cu un produs
   generic de 0.01 lei, plătit prin metoda respectivă.
6. **"Raport X"** / **"Raport Z"** — emit rapoartele corespunzătoare.
7. Jos, în zona de log, vezi fiecare comandă trimisă și răspunsul primit — util pentru
   depanare dacă ceva nu merge.

## Dacă ceva nu merge

- **Codul operator/parola** sunt fixate în cod la `30` / `0030` (cele confirmate
  funcționale pe acest aparat). Dacă se schimbă pe alt aparat, trebuie modificate
  direct în `MainActivity.kt`, în constantele `OP_CODE` / `OP_PWD`, apoi retrimis
  proiectul pe GitHub ca să se recompileze automat.
- Dacă la "Conectare USB" apare "Niciun dispozitiv USB detectat" — verifică fizic
  cablul/adaptorul OTG (vezi discuția anterioară despre asta).
- Dacă apare "Nu am putut revendica interfața USB" — ar fi neașteptat pe acest API
  (spre deosebire de WebUSB, ar trebui să funcționeze prin `forceClaim`), dar dacă
  totuși apare, trimite-mi mesajul exact de eroare.
