/**
 * A real TOTP, computed in the Postman sandbox.
 *
 * Aminata has an authenticator enrolled, so her sign-in needs a six-digit code
 * that is correct for the current thirty-second step. The alternatives were
 * both worse: turning her second factor off would delete the only account on
 * this platform that has one, and burning a recovery code would make the
 * collection pass once and fail every time after.
 *
 * CryptoJS is a global in the Postman sandbox, so this needs nothing installed.
 */

module.exports = (secret, variable) => `
function base32ToHex(base32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const character of base32.replace(/=+$/, '').toUpperCase()) {
    const value = alphabet.indexOf(character);
    if (value < 0) continue;
    bits += value.toString(2).padStart(5, '0');
  }
  let hex = '';
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    hex += parseInt(bits.substr(i, 8), 2).toString(16).padStart(2, '0');
  }
  return hex;
}

// RFC 6238: HMAC-SHA1 over the thirty-second step number, then the dynamic
// truncation that turns twenty bytes into six digits.
const counter = Math.floor(Date.now() / 1000 / 30).toString(16).padStart(16, '0');
const digest = CryptoJS.HmacSHA1(
  CryptoJS.enc.Hex.parse(counter),
  CryptoJS.enc.Hex.parse(base32ToHex(${JSON.stringify(secret)}))
).toString(CryptoJS.enc.Hex);
const offset = parseInt(digest.substr(digest.length - 1), 16);
const truncated = (parseInt(digest.substr(offset * 2, 8), 16) & 0x7fffffff) % 1000000;
pm.collectionVariables.set(${JSON.stringify(variable)}, truncated.toString().padStart(6, '0'));
`;
