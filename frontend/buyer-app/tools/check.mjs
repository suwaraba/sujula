/*
 * What `tsc --noEmit` does for the other four clients.
 *
 * This one has no compiler, so the two mistakes a compiler would have caught
 * are checked here instead: a file that does not parse, and an import of a
 * name nothing exports. The second is the one that actually happens — a
 * function gets renamed and four screens keep importing the old name, and in
 * an ES module that is a silent `undefined` until the screen is opened.
 *
 *     npm run check
 */

import { readdir, readFile } from 'node:fs/promises';
import { join, resolve, dirname } from 'node:path';
import { execFileSync } from 'node:child_process';

const ROOT = resolve(import.meta.dirname, '..');

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...await walk(path));
    else if (path.endsWith('.js') || path.endsWith('.mjs')) out.push(path);
  }
  return out;
}

const NAMED_EXPORT = /export\s+(?:async\s+)?(?:function|const|let|var|class)\s+([A-Za-z0-9_$]+)/g;
const EXPORT_LIST = /export\s*\{([^}]+)\}/g;
const IMPORT_LIST = /import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"]/g;

const files = [...await walk(join(ROOT, 'src')), join(ROOT, 'dev-server.mjs'),
               join(ROOT, 'tools/check.mjs')];
const exported = new Map();
const problems = [];

// Does it parse? An ES module is only parsed when it is first imported, so a
// syntax error in a screen nobody opened ships happily.
for (const file of files) {
  try {
    execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
  } catch (error) {
    problems.push(`${rel(file)}: does not parse\n      ${String(error.stderr).trim().split('\n')[2] || ''}`);
  }
}

for (const file of files) {
  const source = await readFile(file, 'utf8');
  const names = new Set();
  for (const match of source.matchAll(NAMED_EXPORT)) names.add(match[1]);
  for (const match of source.matchAll(EXPORT_LIST)) {
    match[1].split(',').forEach(part => {
      const name = part.trim().split(/\s+as\s+/).pop().trim();
      if (name) names.add(name);
    });
  }
  exported.set(file, names);
}

for (const file of files) {
  const source = await readFile(file, 'utf8');

  for (const match of source.matchAll(IMPORT_LIST)) {
    const specifier = match[2];
    if (!specifier.startsWith('.')) continue;          // node: and bare are not ours
    const target = resolve(dirname(file), specifier);
    if (!exported.has(target)) {
      problems.push(`${rel(file)}: imports ${specifier}, which is not a file here`);
      continue;
    }
    for (const part of match[1].split(',')) {
      const name = part.trim().split(/\s+as\s+/)[0].trim();
      if (!name) continue;
      if (!exported.get(target).has(name)) {
        problems.push(`${rel(file)}: imports { ${name} } from ${specifier}, which does not export it`);
      }
    }
  }
}

function rel(file) {
  return file.slice(ROOT.length + 1);
}

if (problems.length) {
  problems.forEach(problem => console.error('  ' + problem));
  console.error(`\n${problems.length} problem${problems.length === 1 ? '' : 's'}.`);
  process.exit(1);
}

console.log(`${files.length} files, every import resolves.`);
