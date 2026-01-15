import esbuild from "esbuild";

// Multiple entry points für verschiedene Seiten
const entryPoints = {
  "info": "src/main/resources/static/ts/pages/info.ts",
  "enroll": "src/main/resources/static/ts/pages/enroll.ts",
  "login": "src/main/resources/static/ts/pages/login.ts"
};

esbuild.build({
  entryPoints,
  bundle: true,
  format: "esm",
  target: "es2020",
  outdir: "src/main/resources/static/js",
  entryNames: "[name].bundle",
  sourcemap: true,
  // Gemeinsame Abhängigkeiten extrahieren (optional, für Code-Sharing)
  splitting: true
}).catch(() => process.exit(1));
