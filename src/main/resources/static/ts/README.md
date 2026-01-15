# TypeScript Multi-Page Build Setup

Diese neue Struktur erlaubt es, verschiedene TypeScript-Dateien für verschiedene Seiten unabhängig zu bauen.

## Struktur

```
src/main/resources/static/
├── ts/
│   ├── shared.ts                 # Gemeinsame Utilities (getById, onReady, etc.)
│   ├── util/                     # Shared Utilities Module
│   │   ├── http-util.ts
│   │   ├── keys-util.ts
│   │   └── token-util.ts
│   └── pages/                    # Page-spezifische Entry Points
│       ├── enroll.ts
│       ├── info.ts
│       └── login.ts
└── js/                           # Build Output
    ├── enroll.bundle.js
    ├── info.bundle.js
    └── login.bundle.js
```

## Build-Befehle

### Einmalig bauen
```bash
npm run build
```

### Development mit Watch-Modus
```bash
npm run dev
```

Dies wird automatisch esbuild mit mehreren Entry Points ausführen und für jede Seite einen separaten Bundle erzeugen.

## HTML Integration

Jede HTML-Seite lädt ihr spezifisches Bundle:

```html
<!-- enroll-page.html -->
<script type="module" th:src="@{__${basepath}__/js/enroll.bundle.js}"></script>

<!-- info-page.html -->
<script type="module" th:src="@{__${basepath}__/js/info.bundle.js}"></script>

<!-- login-page.html (falls vorhanden) -->
<script type="module" th:src="@{__${basepath}__/js/login.bundle.js}"></script>
```

## Neue Seiten hinzufügen

1. Neue Entry Point Datei erstellen: `src/main/resources/static/ts/pages/mypage.ts`
2. `build.mjs` aktualisieren - `entryPoints` erweitern
3. HTML-Seite mit dem entsprechenden Script-Tag versehen

## Gemeinsame Code teilen

Nutze `shared.ts` und `util/` für Code, der über mehrere Seiten verwendet wird:

```typescript
// In pages/enroll.ts
import { getById, onReady } from "../shared.js";
import { createNewKeyPair } from "../util/keys-util.js";
```
