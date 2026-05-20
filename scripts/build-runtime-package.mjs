import fs from "node:fs";
import path from "node:path";

const repoRoot = process.cwd();
const outputDir = path.join(repoRoot, "dist", "runtime-compose");
const excludedDirectories = new Set([
  ".git",
  ".gradle",
  ".gradle-user",
  "build",
  "dist",
  "node_modules",
  ".idea",
  ".vscode",
  ".kotlin",
  ".swift-module-cache"
]);

function shouldCopy(source) {
  const relativePath = path.relative(repoRoot, source);

  if (!relativePath) {
    return true;
  }

  return !relativePath.split(path.sep).some((segment) => excludedDirectories.has(segment));
}

fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(outputDir, { recursive: true });

for (const entry of fs.readdirSync(repoRoot)) {
  const source = path.join(repoRoot, entry);

  if (!shouldCopy(source)) {
    continue;
  }

  fs.cpSync(source, path.join(outputDir, entry), {
    recursive: true,
    filter: shouldCopy
  });
}

console.log(`Runtime package built: ${path.relative(repoRoot, outputDir)}`);
