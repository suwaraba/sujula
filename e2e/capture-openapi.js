#!/usr/bin/env node
/**
 * Reduces the running server's OpenAPI document to endpoints.json.
 *
 *     node capture-openapi.js          against http://localhost:8080
 *     BASE_URL=... node capture-openapi.js
 *
 * The inventory is committed rather than fetched at build time, because
 * building the collection must not require a running server — otherwise the
 * collection cannot be rebuilt on a machine that cannot start one.
 *
 * It is reduced rather than saved whole for two reasons. The full document is a
 * megabyte of resolved schemas that churns on every unrelated rebuild, and the
 * matrix needs none of it: what it needs is which paths exist, which methods
 * each answers, and what has to go in the path. Keeping only that leaves a file
 * somebody can read in a diff and see that an endpoint appeared.
 *
 * Re-run this after adding an endpoint, and the matrix covers it on the next
 * build without anybody having to remember it exists — which is the whole
 * argument for generating that folder rather than writing it.
 */

const fs = require('fs');
const path = require('path');

const METHODS = ['get', 'post', 'put', 'patch', 'delete'];
const base = process.env.BASE_URL || 'http://localhost:8080';

fetch(base + '/openapi.json')
  .then((response) => {
    if (!response.ok) throw new Error(base + '/openapi.json answered ' + response.status);
    return response.json();
  })
  .then((document) => {
    const endpoints = [];
    Object.keys(document.paths).sort().forEach((template) => {
      const operations = document.paths[template];
      METHODS.forEach((method) => {
        const operation = operations[method];
        if (!operation) return;
        const entry = {
          method: method.toUpperCase(),
          path: template,
        };
        if (operation.operationId) entry.operationId = operation.operationId;
        if (operation.summary) entry.summary = operation.summary;
        const query = (operation.parameters || [])
          .filter((parameter) => parameter.in === 'query')
          .map((parameter) => parameter.name + (parameter.required ? '!' : ''));
        if (query.length) entry.query = query;
        const body = ((((operation.requestBody || {}).content || {})['application/json'] || {}).schema || {});
        if (body.$ref) entry.body = body.$ref.split('/').pop();
        else if (operation.requestBody) entry.body = '(not json)';
        endpoints.push(entry);
      });
    });

    const output = path.join(__dirname, 'endpoints.json');
    fs.writeFileSync(output, JSON.stringify({
      capturedFrom: base,
      operations: endpoints.length,
      endpoints,
    }, null, 2) + '\n');
    process.stdout.write('endpoints.json: ' + endpoints.length + ' operations, '
      + (fs.statSync(output).size / 1024).toFixed(0) + ' KB\n');
  })
  .catch((error) => {
    process.stderr.write(String(error.message) + '\n'
      + 'Start a server first:  mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e\n');
    process.exit(1);
  });
