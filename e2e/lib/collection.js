/**
 * Turns declarative requests into a Postman collection.
 *
 * A suite file says what a request is and what must be true of the answer; this
 * file turns that into the item shape Postman reads and the test script newman
 * runs. Writing the scripts by hand was the alternative, and it produces six
 * hundred near-identical twelve-line programs in which the one that matters is
 * indistinguishable from the five hundred that do not.
 */

const { personas } = require('./personas');

/**
 * Written into every generated script, so assertions share one implementation.
 *
 * Two names, and both are in scope for a hand-written `script` as well:
 *
 *   H       the assertion helpers — H.get, H.matches, H.text
 *   $body   the response parsed as JSON, or null when it is not JSON
 *
 * The dollar sign is not decoration. A generated script and a hand-written one
 * are concatenated into a single program, so a `const body` in each is a
 * SyntaxError that kills the whole script — including the captures at the
 * bottom, which is how one duplicate declaration silently unset a variable
 * five later requests depended on. A prefixed name cannot collide with
 * anything a suite would naturally call its own.
 */
const HELPERS_PRELUDE = "const H = eval(pm.collectionVariables.get('helpers'));\n"
  + 'const $body = H.json(pm.response);\n';

/**
 * Paths the application exempts from CSRF, as SecurityConfig lists them.
 *
 * Sending the header everywhere would work — an ignored path ignores it — but
 * then the collection could not tell the difference between an endpoint that
 * checks the token and one that does not, and a rule accidentally deleted from
 * that ignore list would go unnoticed. Knowing which is which is also what lets
 * the identity suite assert that a write WITHOUT the header is refused.
 */
const CSRF_EXEMPT = [
  /^\/webhooks\//,
  /^\/api\/payments\/callback$/,
  /^\/auth(\/|$)/,
  /^\/me(\/|$)/,
  /^\/geo(\/|$)/,
  /^\/delivery(\/|$)/,
  /^\/delivery-contexts(\/|$)/,
  /^\/currencies(\/|$)/,
  /^\/carts(\/|$)/,
  /^\/checkout(\/|$)/,
];

function csrfExempt(path) {
  return CSRF_EXEMPT.some((pattern) => pattern.test(path));
}

/** Postman's own URL shape: a raw string plus the parsed pieces it indexes on. */
function url(path, query) {
  const raw = '{{baseUrl}}' + path;
  const [bare, inlineQuery] = path.split('?');
  const parsed = {
    raw: query && Object.keys(query).length
      ? raw + (raw.includes('?') ? '&' : '?')
        + Object.entries(query).map(([k, v]) => `${k}=${encodeURI(String(v))}`).join('&')
      : raw,
    host: ['{{baseUrl}}'],
    path: bare.split('/').filter(Boolean),
  };
  const pairs = [];
  if (inlineQuery) {
    inlineQuery.split('&').forEach((pair) => {
      const index = pair.indexOf('=');
      pairs.push(index < 0 ? { key: pair, value: '' }
                           : { key: pair.slice(0, index), value: pair.slice(index + 1) });
    });
  }
  if (query) {
    Object.entries(query).forEach(([key, value]) => pairs.push({ key, value: String(value) }));
  }
  if (pairs.length) parsed.query = pairs;
  return parsed;
}

/**
 * Builds the test script for one request from its expectations.
 *
 * Each expectation becomes its own pm.test, so a run that goes red names the
 * one thing that changed rather than failing a single assertion called
 * "response is correct".
 */
function script(item, expect) {
  const lines = [HELPERS_PRELUDE];
  const title = (what) => JSON.stringify(`${item.name} — ${what}`);

  if (expect.status !== undefined) {
    const statuses = Array.isArray(expect.status) ? expect.status : [expect.status];
    lines.push(
      `pm.test(${title(statuses.length === 1 ? String(statuses[0]) : statuses.join(' or '))}, function () {`,
      `  pm.expect(${JSON.stringify(statuses)}).to.include(pm.response.code,`,
      `    'answered ' + pm.response.code + ': ' + H.text(pm.response).slice(0, 300));`,
      '});');
  }

  if (expect.header) {
    Object.entries(expect.header).forEach(([name, value]) => {
      lines.push(
        `pm.test(${title(`header ${name}`)}, function () {`,
        `  const actual = pm.response.headers.get(${JSON.stringify(name)});`,
        `  pm.expect(H.matches(actual, ${JSON.stringify(value)}), ${JSON.stringify(name)} + ' was ' + H.show(actual)).to.be.true;`,
        '});');
    });
  }

  if (expect.json) {
    Object.entries(expect.json).forEach(([path, value]) => {
      // A value written as {{something}} is a variable captured by an earlier
      // request. Postman substitutes those in URLs and bodies but NOT inside a
      // test script, so it has to be resolved here — otherwise the assertion
      // compares the response against the literal seven characters "{{id}}"
      // and fails on a correct answer.
      const expected = typeof value === 'string' && value.includes('{{')
        ? `pm.variables.replaceIn(${JSON.stringify(value)})`
        : JSON.stringify(value);
      lines.push(
        `pm.test(${title(path + ' ' + descriptionOf(value))}, function () {`,
        `  const actual = H.get($body, ${JSON.stringify(path)});`,
        `  const expected = ${expected};`,
        `  pm.expect(H.matches(actual, expected),`,
        `    ${JSON.stringify(path)} + ' was ' + H.show(actual) + ', expected ' + H.describe(expected)).to.be.true;`,
        '});');
    });
  }

  if (expect.absent) {
    // "This field is not in the response" — the assertion that catches a
    // widening DTO handing a driver the payer's name six months from now.
    expect.absent.forEach((path) => {
      lines.push(
        `pm.test(${title(path + ' is withheld')}, function () {`,
        `  const actual = H.get($body, ${JSON.stringify(path)});`,
        `  pm.expect(actual === undefined || actual === null, ${JSON.stringify(path)} + ' was ' + H.show(actual)).to.be.true;`,
        '});');
    });
  }

  if (expect.bodyExcludes) {
    expect.bodyExcludes.forEach(({ value, why }) => {
      lines.push(
        `pm.test(${title(`the body never says ${JSON.stringify(value)}${why ? ' — ' + why : ''}`)}, function () {`,
        `  pm.expect(H.text(pm.response).indexOf(${JSON.stringify(value)}) < 0,`,
        `    'found ' + ${JSON.stringify(JSON.stringify(value))} + ' in the response').to.be.true;`,
        '});');
    });
  }

  if (expect.bodyIncludes) {
    expect.bodyIncludes.forEach((value) => {
      lines.push(
        `pm.test(${title(`the body says ${JSON.stringify(value)}`)}, function () {`,
        `  pm.expect(H.text(pm.response)).to.include(${JSON.stringify(value)});`,
        '});');
    });
  }

  if (expect.capture) {
    // Captures run outside pm.test: a later request needing this value should
    // fail on the value being missing, not be skipped because an assertion
    // above it went red first.
    Object.entries(expect.capture).forEach(([variable, path]) => {
      lines.push(
        `pm.collectionVariables.set(${JSON.stringify(variable)},`,
        `  H.get($body, ${JSON.stringify(path)}));`);
    });
  }

  if (expect.script) lines.push(expect.script.trim());

  return lines.join('\n');
}

function descriptionOf(value) {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    return Object.keys(value)[0].replace('$', '');
  }
  return 'is ' + JSON.stringify(value);
}

/**
 * One request.
 *
 * `as` names a persona and attaches that person's token; leaving it out makes
 * the request anonymous, which on this platform is a real caller rather than a
 * mistake.
 */
function req(spec) {
  const {
    name, method = 'GET', path, query, as, headers = {}, body, rawBody,
    contentType = 'application/json', note, ...expect
  } = spec;

  if (!path) throw new Error(`request ${name} has no path`);
  if (as && !personas[as]) throw new Error(`request ${name} names unknown persona ${as}`);

  const header = [];
  if (as && personas[as].token) {
    header.push({ key: 'Authorization', value: `Bearer {{${personas[as].token}}}` });
  }
  if ((body !== undefined || rawBody !== undefined) && contentType) {
    header.push({ key: 'Content-Type', value: contentType });
  }
  if (method !== 'GET' && method !== 'HEAD' && !csrfExempt(path) && headers['X-XSRF-TOKEN'] === undefined) {
    header.push({ key: 'X-XSRF-TOKEN', value: '{{csrfToken}}' });
  }
  Object.entries(headers).forEach(([key, value]) => {
    if (value !== null) header.push({ key, value: String(value) });
  });

  const request = {
    method,
    header,
    url: url(path, query),
    description: note,
  };
  if (rawBody !== undefined) {
    request.body = { mode: 'raw', raw: rawBody };
  } else if (body !== undefined) {
    request.body = {
      mode: 'raw',
      raw: typeof body === 'string' ? body : JSON.stringify(body, null, 2),
      options: { raw: { language: 'json' } },
    };
  }

  const item = { name, request };
  const events = [];
  if (spec.preScript) {
    events.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: spec.preScript.trim().split('\n') } });
  }
  const testSource = script(item, expect);
  if (testSource.trim() !== HELPERS_PRELUDE.trim()) {
    events.push({ listen: 'test', script: { type: 'text/javascript', exec: testSource.split('\n') } });
  }
  if (events.length) item.event = events;
  return item;
}

/** A folder, which is how a run's output stays readable at six hundred requests. */
function folder(name, description, items) {
  return { name, description, item: items.filter(Boolean) };
}

/** The collection itself. */
function collection(name, description, folders, variables) {
  return {
    info: {
      name,
      description,
      schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    },
    item: folders.filter(Boolean),
    variable: Object.entries(variables).map(([key, value]) => ({ key, value: String(value) })),
  };
}

module.exports = { req, folder, collection, csrfExempt, HELPERS_PRELUDE };
