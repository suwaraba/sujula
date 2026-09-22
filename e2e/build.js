#!/usr/bin/env node
/**
 * Assembles the Postman collection from the suites.
 *
 *     node build.js
 *
 * Writes sujula.postman_collection.json, which is committed: the file is the
 * deliverable, importable into the Postman app by somebody who has never run
 * this script. Rebuilding is only needed after editing a suite.
 */

const fs = require('fs');
const path = require('path');

const { collection } = require('./lib/collection');
const helpers = require('./lib/helpers');

const SUITES = fs
  .readdirSync(path.join(__dirname, 'suites'))
  .filter((file) => file.endsWith('.js'))
  .sort();

const folders = SUITES.map((file) => require(path.join(__dirname, 'suites', file)));

/**
 * Collection variables.
 *
 * Every token starts empty. A run that skips the identity folder therefore
 * sends `Bearer ` with nothing after it and is refused, which is the right
 * failure — the alternative, a stale token left in the file, is a collection
 * that passes for a reason nobody can see.
 */
const variables = {
  baseUrl: 'http://localhost:8080',
  helpers,
  csrfToken: '',
};
folders.forEach(function collect(node) {
  (node.item || []).forEach((item) => {
    if (item.item) return collect(item);
    const auth = (item.request.header || []).find((h) => h.key === 'Authorization');
    const match = auth && /^Bearer \{\{(\w+)\}\}$/.exec(auth.value);
    if (match) variables[match[1]] = '';
  });
});
// Captured alongside each token by the identity folder, and used by the
// sign-out and session assertions.
Object.keys(variables)
  .filter((key) => key.startsWith('token'))
  .forEach((key) => {
    variables[key + 'Refresh'] = variables[key + 'Refresh'] || '';
    variables[key + 'Session'] = variables[key + 'Session'] || '';
  });

const built = collection(
  'Sujula — every endpoint, every caller',
  fs.readFileSync(path.join(__dirname, 'COLLECTION.md'), 'utf8'),
  folders,
  variables,
);

const output = path.join(__dirname, 'sujula.postman_collection.json');
fs.writeFileSync(output, JSON.stringify(built, null, 2) + '\n');

let requests = 0;
let assertions = 0;
(function count(node) {
  (node.item || []).forEach((item) => {
    if (item.item) return count(item);
    requests += 1;
    const test = (item.event || []).find((e) => e.listen === 'test');
    if (test) assertions += test.script.exec.filter((line) => line.startsWith('pm.test(')).length;
  });
})(built);

process.stdout.write(
  `${path.basename(output)}: ${folders.length} folders, ${requests} requests, `
  + `${assertions} assertions, ${(fs.statSync(output).size / 1024).toFixed(0)} KB\n`,
);
