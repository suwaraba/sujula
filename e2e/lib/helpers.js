/**
 * The assertion helpers, as source.
 *
 * Postman has no module system: a test script is a standalone program, and the
 * only way to share code between six hundred of them is to put the source in a
 * collection variable and evaluate it. So this file is a string, and every
 * generated test script opens with
 *
 *     const H = eval(pm.collectionVariables.get('helpers'));
 *
 * It is an expression rather than a set of declarations on purpose — an eval'd
 * function declaration lands in whatever scope the sandbox happens to give the
 * script, and that is not a thing to depend on six hundred times.
 */

module.exports = `(function () {
  /**
   * Reads a dotted path, treating a numeric segment as an array index.
   * Returns undefined rather than throwing, so a missing field fails as a
   * readable assertion instead of as a script error with no context.
   */
  function get(object, path) {
    if (path === '' || path == null) return object;
    var cursor = object;
    var parts = String(path).split('.');
    for (var i = 0; i < parts.length; i++) {
      if (cursor === null || cursor === undefined) return undefined;
      cursor = cursor[parts[i]];
    }
    return cursor;
  }

  /** The response as JSON, or null when the body is not JSON at all. */
  function json(response) {
    try { return response.json(); } catch (e) { return null; }
  }

  /** Describes a value briefly enough to read in a failure message. */
  function show(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    if (typeof value === 'string') return JSON.stringify(value.length > 80 ? value.slice(0, 80) + '…' : value);
    if (Array.isArray(value)) return 'an array of ' + value.length;
    if (typeof value === 'object') return JSON.stringify(value).slice(0, 120);
    return String(value);
  }

  /**
   * Compares an actual value against a matcher.
   *
   * A plain value means equality. An object with a $-prefixed key is a matcher,
   * because the alternative — passing functions — cannot survive being written
   * into a collection file as JSON.
   */
  function matches(actual, expected) {
    if (expected !== null && typeof expected === 'object' && !Array.isArray(expected)) {
      var key = Object.keys(expected)[0];
      var argument = expected[key];
      switch (key) {
        case '$exists':   return argument ? actual !== undefined && actual !== null
                                          : actual === undefined || actual === null;
        case '$ne':       return JSON.stringify(actual) !== JSON.stringify(argument);
        case '$gt':       return typeof actual === 'number' && actual > argument;
        case '$gte':      return typeof actual === 'number' && actual >= argument;
        case '$lt':       return typeof actual === 'number' && actual < argument;
        case '$lte':      return typeof actual === 'number' && actual <= argument;
        case '$type':     return argument === 'array' ? Array.isArray(actual) : typeof actual === argument;
        case '$length':   return actual != null && actual.length === argument;
        case '$minLength':return actual != null && actual.length >= argument;
        case '$includes': return Array.isArray(actual)
                                 ? actual.some(function (e) { return JSON.stringify(e) === JSON.stringify(argument); })
                                 : String(actual).indexOf(argument) >= 0;
        case '$excludes': return Array.isArray(actual)
                                 ? !actual.some(function (e) { return JSON.stringify(e) === JSON.stringify(argument); })
                                 : String(actual).indexOf(argument) < 0;
        case '$oneOf':    return argument.some(function (e) { return JSON.stringify(e) === JSON.stringify(actual); });
        case '$matches':  return new RegExp(argument).test(String(actual));
        case '$closeTo':  return typeof actual === 'number'
                                 && Math.abs(actual - argument[0]) <= argument[1];
        default: throw new Error('unknown matcher ' + key);
      }
    }
    // A captured variable arrives as a string, because that is all a Postman
    // variable can hold — so an assertion written as {{orderId}} is compared
    // against a JSON number and fails on a correct answer. Coerced only when
    // exactly one side is a number and the other is a string that reads as the
    // same number, which cannot turn a genuine mismatch into a pass.
    if (typeof actual === 'number' && typeof expected === 'string') {
      return String(actual) === expected.trim();
    }
    if (typeof actual === 'string' && typeof expected === 'number') {
      return actual.trim() === String(expected);
    }
    return JSON.stringify(actual) === JSON.stringify(expected);
  }

  /** Renders a matcher as the phrase a failure message wants. */
  function describe(expected) {
    if (expected !== null && typeof expected === 'object' && !Array.isArray(expected)) {
      var key = Object.keys(expected)[0];
      var argument = expected[key];
      switch (key) {
        case '$exists':   return argument ? 'to be present' : 'to be absent';
        case '$ne':       return 'not to be ' + show(argument);
        case '$gt':       return 'to be more than ' + argument;
        case '$gte':      return 'to be at least ' + argument;
        case '$lt':       return 'to be less than ' + argument;
        case '$lte':      return 'to be at most ' + argument;
        case '$type':     return 'to be ' + (argument === 'array' ? 'an array' : 'a ' + argument);
        case '$length':   return 'to have ' + argument + ' entries';
        case '$minLength':return 'to have at least ' + argument + ' entries';
        case '$includes': return 'to include ' + show(argument);
        case '$excludes': return 'not to include ' + show(argument);
        case '$oneOf':    return 'to be one of ' + show(argument);
        case '$matches':  return 'to match /' + argument + '/';
        case '$closeTo':  return 'to be ' + argument[0] + ' give or take ' + argument[1];
      }
    }
    return 'to be ' + show(expected);
  }

  /**
   * The body, whatever it is, as a searchable string.
   *
   * Used by the "must not contain" assertions, which are how this collection
   * checks that a response withheld something — a recipient's street on a
   * seller's screen, a payer's name on a driver's. Those are assertions about
   * the bytes rather than about a field, because the field that leaks is the
   * one nobody thought to name.
   */
  function text(response) {
    try { return response.text(); } catch (e) { return ''; }
  }

  return { get: get, json: json, show: show, matches: matches, describe: describe, text: text };
})()`;
