// Regenerates PROJECT-INDEX.md for a Kotlin/Gradle project. REGEX/brace-depth
// based, NOT a full AST (no JVM/kotlinc dependency — avoids that toolchain
// entirely). Tracks brace depth to index declarations at file top-level and
// one level inside a class/object/interface body (typical members); strings
// and comments are masked before depth-tracking so braces inside them don't
// throw off the count. KDoc (/** */) immediately preceding a declaration is
// captured as a one-line summary. Reverse "imported by" index is built by
// matching `import pkg.Name` against every top-level declaration's own
// package+name across the project — best-effort, not a full resolver.
//
// Usage: node scripts/gen-index-kotlin.mjs [projectRoot] [outFile]
// Shared master copy — C:\Users\PC\.claude\scripts\gen-index-kotlin.mjs
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, relative, extname, resolve } from 'node:path';

const PROJECT_DIR = process.argv[2] ? resolve(process.argv[2]) : join(import.meta.dirname, '..');
const OUT_FILE = process.argv[3] ? resolve(process.argv[3]) : join(PROJECT_DIR, 'PROJECT-INDEX.md');
const EXCLUDE_DIRS = new Set(['build', '.gradle', '.idea', '.kotlin', '.git', 'node_modules', '.claude']);
const LARGE_FILE_LINES = 300;

function walk(dir, files = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!EXCLUDE_DIRS.has(entry.name) && !entry.name.startsWith('.')) walk(join(dir, entry.name), files);
    } else if (extname(entry.name) === '.kt') {
      files.push(join(dir, entry.name));
    }
  }
  return files;
}

// Blank out string/char literal and comment contents (keep newlines, keep length)
// so brace-depth tracking and keyword matching never trip on braces/keywords
// that only appear inside them.
function mask(text) {
  let out = '';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i], c2 = text[i + 1];
    if (c === '/' && c2 === '/') {
      out += '//'; i += 2;
      while (i < n && text[i] !== '\n') { out += ' '; i++; }
    } else if (c === '/' && c2 === '*') {
      // Keep the /* and */ delimiters as real characters (only blank the
      // CONTENT between them) — kdocBefore()'s backward scan needs to see
      // an actual "*/" boundary, not indistinguishable whitespace.
      out += '/*'; i += 2;
      while (i < n && !(text[i] === '*' && text[i + 1] === '/')) { out += text[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '*/'; i += 2; }
    } else if (c === '"' && text[i + 1] === '"' && text[i + 2] === '"') {
      out += '   '; i += 3;
      while (i < n && !(text[i] === '"' && text[i + 1] === '"' && text[i + 2] === '"')) { out += text[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '   '; i += 3; }
    } else if (c === '"') {
      out += ' '; i++;
      while (i < n && text[i] !== '"') {
        if (text[i] === '\\') { out += '  '; i += 2; continue; }
        out += text[i] === '\n' ? '\n' : ' '; i++;
      }
      if (i < n) { out += ' '; i++; }
    } else if (c === "'") {
      out += ' '; i++;
      while (i < n && text[i] !== "'") {
        if (text[i] === '\\') { out += '  '; i += 2; continue; }
        out += ' '; i++;
      }
      if (i < n) { out += ' '; i++; }
    } else {
      out += c; i++;
    }
  }
  return out;
}

function lineAt(text, pos) {
  return text.slice(0, pos).split('\n').length;
}

function kdocBefore(rawText, maskedText, declPos) {
  // Walk backwards from declPos over whitespace/annotation lines to see if a
  // /** ... */ block (in the ORIGINAL text, since mask() blanked its content)
  // ends right before it (own-line only, not a same-line trailing comment).
  let end = declPos;
  while (end > 0 && /\s/.test(maskedText[end - 1])) end--;
  // skip back over one or more single-line annotations like @Test on their own line
  let searchEnd = end;
  for (let guard = 0; guard < 5; guard++) {
    const lineStart = maskedText.lastIndexOf('\n', searchEnd - 1) + 1;
    const line = rawText.slice(lineStart, searchEnd).trim();
    if (/^@[\w.]+(\([^)]*\))?$/.test(line)) { searchEnd = lineStart; while (searchEnd > 0 && /\s/.test(maskedText[searchEnd - 1])) searchEnd--; continue; }
    break;
  }
  if (rawText.slice(Math.max(0, searchEnd - 2), searchEnd) !== '*/') return null;
  const start = rawText.lastIndexOf('/**', searchEnd);
  if (start < 0) return null;
  const block = rawText.slice(start, searchEnd);
  const lineStart = rawText.lastIndexOf('\n', start) + 1;
  if (rawText.slice(lineStart, start).trim() !== '') return null; // trailing comment on a previous statement's line
  const firstLine = block.split('\n').map((l) => l.replace(/^[\s*/]+/, '').replace(/\s*\*+\/\s*$/, '').trim()).find((l) => l && !l.startsWith('@'));
  return firstLine || null;
}

function matchParens(text, openPos) {
  // text[openPos] === '('; returns index right after the matching ')'
  let depth = 0, i = openPos;
  for (; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')') { depth--; if (depth === 0) return i + 1; }
  }
  return -1;
}

const DECL_RE = /(?:^|\n)([ \t]*)((?:@[\w.]+(?:\([^\n]*\))?\s+)*(?:(?:public|private|internal|protected|open|abstract|final|sealed|inline|suspend|override|actual|expect|external|inner|data|enum|annotation|companion|const|lateinit|crossinline|noinline|vararg|tailrec|infix|operator)\s+)*)(class|object|interface|fun|val|var|typealias)\s+(\w+)/g;

function extractFile(rawText) {
  const masked = mask(rawText);
  const declarations = [];
  const imports = [];
  let pkg = '';

  const pkgMatch = masked.match(/(?:^|\n)\s*package\s+([\w.]+)/);
  if (pkgMatch) pkg = pkgMatch[1];
  for (const m of masked.matchAll(/(?:^|\n)\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?/g)) {
    imports.push({ fqn: m[1], alias: m[2] || m[1].split('.').pop() });
  }

  // brace depth at each character position, computed once
  const depthAt = new Int32Array(masked.length + 1);
  let depth = 0;
  for (let i = 0; i < masked.length; i++) {
    depthAt[i] = depth;
    if (masked[i] === '{') depth++;
    else if (masked[i] === '}') depth--;
  }

  for (const m of masked.matchAll(DECL_RE)) {
    const fullMatchStart = m.index + m[0].indexOf(m[3], m[1].length + m[2].length);
    const declPos = fullMatchStart;
    const d = depthAt[declPos] ?? 0;
    if (d > 1) continue; // only top-level and one level inside a class/object body
    const modifiers = m[2].trim();
    const keyword = m[3];
    const name = m[4];
    const nameEnd = declPos + keyword.length + 1 + name.length;

    let sig = '';
    if (keyword === 'fun') {
      const openParen = masked.indexOf('(', nameEnd);
      if (openParen >= 0 && openParen < nameEnd + 20) {
        const closeIdx = matchParens(masked, openParen);
        if (closeIdx > 0) {
          const params = rawText.slice(openParen, closeIdx).replace(/\s+/g, ' ').trim();
          let after = masked.slice(closeIdx).match(/^\s*:\s*([\w<>?.,\[\] ]+?)(?=\s*[={]|\s*$)/);
          const ret = after ? after[1].trim() : '';
          sig = `${params}${ret ? `: ${ret}` : ''}`;
        }
      }
    } else if (keyword === 'val' || keyword === 'var') {
      const after = masked.slice(nameEnd).match(/^\s*:\s*([\w<>?.,\[\] ]+?)(?=\s*[=\n]|$)/);
      if (after) sig = `: ${after[1].trim()}`;
    } else if (keyword === 'class' || keyword === 'interface') {
      // primary constructor params (if any) plus supertype list, e.g.
      // "data class Foo(val x: Int) : Base(), Iface" — the main API surface
      // for a Kotlin class, unlike a bare TS class declaration.
      let cursor = nameEnd;
      const genericMatch = masked.slice(cursor).match(/^\s*<[^{]*?>/);
      if (genericMatch) cursor += genericMatch[0].length;
      let ctorParams = '';
      const openParen = masked.indexOf('(', cursor);
      if (openParen >= 0 && openParen < cursor + 10) {
        const closeIdx = matchParens(masked, openParen);
        if (closeIdx > 0) {
          ctorParams = rawText.slice(openParen, closeIdx).replace(/\s+/g, ' ').trim();
          cursor = closeIdx;
        }
      }
      const superMatch = masked.slice(cursor).match(/^\s*:\s*([^{\n]+)/);
      const superText = superMatch ? superMatch[1].replace(/\s+/g, ' ').trim() : '';
      sig = [ctorParams, superText ? `: ${superText}` : ''].filter(Boolean).join(' ');
    }

    const doc = kdocBefore(rawText, masked, declPos);
    declarations.push({ line: lineAt(masked, declPos), depth: d, keyword, name, modifiers, sig, doc, pkg });
  }

  return { declarations, imports, pkg };
}

const files = walk(PROJECT_DIR).sort();
const perFile = new Map();
// fqn (pkg.Name) -> {file, rel} for depth-0 class/object/interface declarations, for reverse-index resolution
const fqnIndex = new Map();

const scanErrors = [];
for (const file of files) {
  const rawText = readFileSync(file, 'utf8');
  const lineCount = rawText.split('\n').length;
  let declarations = [], imports = [], pkg = '';
  try {
    ({ declarations, imports, pkg } = extractFile(rawText));
  } catch (err) {
    scanErrors.push(file);
    perFile.set(file, { lineCount, declarations: [], imports: [], pkg: '', error: String(err.message || err) });
    continue;
  }
  perFile.set(file, { lineCount, declarations, imports, pkg, error: null });
  for (const d of declarations) {
    if (d.depth === 0 && (d.keyword === 'class' || d.keyword === 'object' || d.keyword === 'interface')) {
      fqnIndex.set(`${pkg}.${d.name}`, file);
    }
  }
}

// reverse index: file -> Set(importer rel path), based on FQN import matches
const importedBy = new Map();
for (const [file, data] of perFile) {
  for (const imp of data.imports) {
    const target = fqnIndex.get(imp.fqn);
    if (!target || target === file) continue;
    const importerRel = relative(PROJECT_DIR, file).replace(/\\/g, '/');
    if (!importedBy.has(target)) importedBy.set(target, new Set());
    importedBy.get(target).add(importerRel);
  }
}

const groups = new Map();
for (const file of files) {
  const rel = relative(PROJECT_DIR, file).replace(/\\/g, '/');
  const topDir = rel.split('/')[0];
  if (!groups.has(topDir)) groups.set(topDir, []);
  groups.get(topDir).push({ file, rel });
}

let body = '';
let totalDecls = 0;
for (const [group, entries] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  body += `\n## ${group}/\n\n`;
  for (const { file, rel } of entries) {
    const { lineCount, declarations, pkg, error } = perFile.get(file);
    const bigFlag = lineCount > LARGE_FILE_LINES ? ' ⚠' : '';
    if (error) {
      body += `### ${rel} (${lineCount} سطر)  ⚠ فشل الفهرسة: ${error.slice(0, 120)}\n\n`;
      continue;
    }
    body += `### ${rel} (${lineCount} سطر)${bigFlag}${pkg ? `  [package ${pkg}]` : ''}\n`;
    for (const d of declarations) {
      totalDecls++;
      const indent = d.depth === 1 ? '  ' : '';
      const mods = d.modifiers ? `${d.modifiers} ` : '';
      const sigPart = d.sig ? ` ${d.sig}` : '';
      const docPart = d.doc ? ` — ${d.doc}` : '';
      body += `${indent}- L${d.line} \`${mods}${d.keyword} ${d.name}${sigPart}\`${docPart}\n`;
      if (d.depth === 0 && (d.keyword === 'class' || d.keyword === 'object' || d.keyword === 'interface')) {
        const users = importedBy.get(file);
        if (users && users.size) body += `${indent}  يُستخدم في: ${[...users].sort().join(', ')}\n`;
      }
    }
    body += '\n';
  }
}

const header = `# فهرس رموز المشروع (مولّد آلياً — قائم على regex/عمق الأقواس، **ليس تحليل AST كامل**)

مولّد بـ \`scripts/gen-index-kotlin.mjs\` — لا تُحرّره يدوياً. يُعاد توليده تلقائياً عند إيقاف/مسح/ضغط الجلسة (Stop hook)، أو يدوياً — شغّل \`node scripts/gen-index-kotlin.mjs\` مباشرة.
⚠ **هذا فهرس نصّي (regex + تتبّع عمق الأقواس)، وليس تحليل AST حقيقياً عبر مترجم Kotlin** (خلافاً لنسخة TypeScript/Python) — لأن الوصول لمترجم Kotlin برمجياً يتطلب JVM + إعداد classpath ثقيل غير متناسب هنا. النتيجة دقيقة غالباً لكنها قد تُخطئ في تواقيع معقّدة جداً (generics متداخلة، sealed hierarchies طويلة). يفهرس مستوى الملف والمستوى الأول داخل class/object/interface فقط (ليس دوال متداخلة أعمق).
ابحث هنا أولاً بـ Grep قبل فتح أي ملف مصدر كامل. سطر \`يُستخدم في:\` تحت أي class/object/interface = تطابق استيراد FQN فعلي، أفضل-جهد وليس مضموناً 100%.
⚠ بجانب اسم ملف = يتجاوز ${LARGE_FILE_LINES} سطر.

**${files.length}** ملف مفحوص، **${totalDecls}** تعريفاً${scanErrors.length ? `، **${scanErrors.length}** ملف فشلت فهرسته` : ''}.

`;

writeFileSync(OUT_FILE, header + body, 'utf8');
console.log(`Wrote ${OUT_FILE}`);
console.log(`${files.length} files scanned, ${totalDecls} declarations indexed`);
