import fs from "node:fs";
import path from "node:path";

const repoRoot = process.cwd();
const manifestPath = path.join(repoRoot, ".portfolio", "manifest.json");
const outputDir = path.join(repoRoot, "dist", "portfolio-package");
const imagesDir = path.join(outputDir, "images");
const releasesDir = path.join(outputDir, "releases");
const documentsDir = path.join(outputDir, "documents");
const requiredFields = ["id", "title", "subtitle", "summary", "stacks", "repoUrl", "article"];

function ensureFile(filePath, label) {
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    throw new Error(`${label} does not exist: ${filePath}`);
  }
}

function toPackagePath(...parts) {
  return `./${parts.join("/")}`;
}

function isExternalPath(url) {
  return /^(https?:)?\/\//.test(url) || url.startsWith("data:") || url.startsWith("#") || url.startsWith("mailto:");
}

function copyUniq(sourcePath, targetDir, preferredName) {
  ensureFile(sourcePath, "source file");

  const ext = path.extname(preferredName || sourcePath);
  const baseName = path.basename(preferredName || sourcePath, ext);
  let targetName = `${baseName}${ext}`;
  let index = 2;

  while (fs.existsSync(path.join(targetDir, targetName))) {
    const current = path.join(targetDir, targetName);

    if (fs.readFileSync(current).equals(fs.readFileSync(sourcePath))) {
      return targetName;
    }

    targetName = `${baseName}-${index}${ext}`;
    index += 1;
  }

  fs.copyFileSync(sourcePath, path.join(targetDir, targetName));
  return targetName;
}

function rewriteMarkdownImages(markdown, articleSourcePath) {
  const articleDir = path.dirname(articleSourcePath);

  return markdown.replace(/(!\[[^\]]*]\()([^\s)]+)(\))/g, (match, prefix, imagePath, suffix) => {
    if (isExternalPath(imagePath)) {
      return match;
    }

    const sourcePath = path.resolve(articleDir, imagePath);
    const copiedName = copyUniq(sourcePath, imagesDir, path.basename(imagePath));
    return `${prefix}${toPackagePath("images", copiedName)}${suffix}`;
  });
}

function normalizeLinks(links) {
  if (!Array.isArray(links)) {
    return [];
  }

  return links.map((link) => {
    if (!link.url || isExternalPath(link.url)) {
      return link;
    }

    const sourcePath = path.resolve(repoRoot, link.url);

    if (!fs.existsSync(sourcePath)) {
      return link;
    }

    if (link.type === "release") {
      const releaseName = copyUniq(sourcePath, releasesDir, path.basename(link.url));
      return {
        ...link,
        url: toPackagePath("releases", releaseName)
      };
    }

    const documentName = copyUniq(sourcePath, documentsDir, path.basename(link.url));
    return {
      ...link,
      url: toPackagePath("documents", documentName)
    };
  });
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));

for (const field of requiredFields) {
  if (!manifest[field]) {
    throw new Error(`Missing required manifest field: ${field}`);
  }
}

const articleSourcePath = path.resolve(repoRoot, manifest.article);
ensureFile(articleSourcePath, "article");

fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(imagesDir, { recursive: true });
fs.mkdirSync(releasesDir, { recursive: true });
fs.mkdirSync(documentsDir, { recursive: true });

const articleMarkdown = fs.readFileSync(articleSourcePath, "utf8");
fs.writeFileSync(path.join(outputDir, "article.md"), rewriteMarkdownImages(articleMarkdown, articleSourcePath));

const packageManifest = {
  ...manifest,
  article: "./article.md",
  updatedAt: new Date().toISOString(),
  links: normalizeLinks(manifest.links)
};

if (manifest.coverImage) {
  const coverSourcePath = path.resolve(repoRoot, manifest.coverImage);
  const coverExt = path.extname(coverSourcePath) || ".png";
  const coverName = copyUniq(coverSourcePath, imagesDir, `cover${coverExt}`);
  packageManifest.coverImage = toPackagePath("images", coverName);
}

fs.writeFileSync(path.join(outputDir, "manifest.json"), `${JSON.stringify(packageManifest, null, 2)}\n`);

console.log(`Portfolio package built: ${path.relative(repoRoot, outputDir)}`);
