// FDS to OpenLDAP migration — helper functions to APPEND to the stock
// sdi_solution_dir/LDAPSync/customScript.js (do NOT overwrite that file).
// None of these names collide with stock LDAPSync functions.
//
// The generic-LDAP migration (see README) needs only: migratePassword and the
// helpers it calls (pwValueToString, pwScheme, hashSSHA, sshaEncode,
// _b64manual). The rest — parseList, isDropped, filterClasses,
// targetObjectClasses, passThroughAttrs, includeNamedAttrs — are not used by
// that migration and are harmless if appended.
//
// Runs in the SDI JS engine; the module.exports guard at the bottom is inert
// there and only active under Node for the unit tests.

var DEFAULT_DROP_LIST = [
  'createtimestamp','modifytimestamp','creatorsname','modifiersname',
  'entryuuid','hassubordinates','subschemasubentry','aclentry','aclpropagate',
  'entryowner','ibm-entryuuid','ibm-slapd*','secretkey','*password*'
];

var ALWAYS_KEEP = ['userpassword','addn'];

function pwScheme(value) {
  if (value == null) return null;
  var s = String(value);
  if (s.length === 0) return null;
  var m = s.match(/^\{([^}]+)\}/);
  return m ? ('{' + m[1].toUpperCase() + '}') : null;
}

function parseList(csv) {
  if (csv == null) return [];
  var out = [];
  var parts = String(csv).split(',');
  for (var i = 0; i < parts.length; i++) {
    var t = parts[i].trim().toLowerCase();
    if (t.length > 0) out.push(t);
  }
  return out;
}

function isDropped(name, dropList) {
  var n = String(name).toLowerCase();
  for (var k = 0; k < ALWAYS_KEEP.length; k++) {
    if (n === ALWAYS_KEEP[k]) return false;
  }
  var list = dropList || DEFAULT_DROP_LIST;
  for (var i = 0; i < list.length; i++) {
    var pat = String(list[i]).toLowerCase();
    if (pat.length >= 2 && pat.charAt(0) === '*' && pat.charAt(pat.length - 1) === '*') {
      if (n.indexOf(pat.substring(1, pat.length - 1)) !== -1) return true;
    } else if (pat.charAt(pat.length - 1) === '*') {
      if (n.indexOf(pat.substring(0, pat.length - 1)) === 0) return true;
    } else if (n === pat) {
      return true;
    }
  }
  return false;
}

function _b64manual(bytes) {
  // Manual base64 encoding (production SDI path). bytes: array of 0..255.
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var out = '', i;
  for (i = 0; i < bytes.length; i += 3) {
    var b0 = bytes[i], b1 = i+1 < bytes.length ? bytes[i+1] : 0, b2 = i+2 < bytes.length ? bytes[i+2] : 0;
    out += chars.charAt(b0 >> 2);
    out += chars.charAt(((b0 & 3) << 4) | (b1 >> 4));
    out += (i+1 < bytes.length) ? chars.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
    out += (i+2 < bytes.length) ? chars.charAt(b2 & 63) : '=';
  }
  return out;
}

function _b64(bytes) {
  // bytes: array of 0..255. Base64 without external deps.
  if (typeof Buffer !== 'undefined') return Buffer.from(bytes).toString('base64'); // Node
  return _b64manual(bytes);
}

function sshaEncode(digestBytes, saltBytes) {
  return '{SSHA}' + _b64(digestBytes.concat(saltBytes));
}

function hashSSHA(cleartext) {
  var s = String(cleartext);
  // Detect the runtime by `require` (Node, tests) — NOT by `typeof java`, which
  // is falsy in SDI's JScript engine and wrongly routed to the Node branch.
  if (typeof require === 'function') {                          // Node (unit tests)
    var crypto = require('node:crypto');
    var saltBuf = crypto.randomBytes(8);
    var digN = crypto.createHash('sha1').update(Buffer.concat([Buffer.from(s, 'utf8'), saltBuf])).digest();
    return sshaEncode(Array.from(digN), Array.from(saltBuf));
  }
  // SDI / Java runtime
  var md = java.security.MessageDigest.getInstance('SHA-1');
  var rnd = new java.security.SecureRandom();
  var salt = rnd.generateSeed(8);   // returns a Java byte[8] directly; engine-agnostic
  md.update(new java.lang.String(s).getBytes('UTF-8'));
  md.update(salt);
  var digest = md.digest();
  var toArr = function (jb) { var a = []; for (var i=0;i<jb.length;i++) a.push(jb[i] & 0xff); return a; };
  return sshaEncode(toArr(digest), toArr(salt));
}

// LDAP userPassword is an octet string, so the source value often arrives as a
// Java byte[] rather than a String. Decode robustly (String, java.lang.String,
// or byte[] -> UTF-8) so migratePassword never throws in the Output map.
function pwValueToString(v) {
  if (v == null) return null;
  if (typeof v === 'string') return v;
  // v may be a Java byte[] (LDAP octet string) or a java.lang.String.
  // new java.lang.String(byte[], "UTF-8") decodes a byte[]; it throws for a
  // java.lang.String (no such constructor), so we fall back to stringify.
  try {
    return '' + new java.lang.String(v, 'UTF-8');
  } catch (e) {
    return '' + v;
  }
}

// A leading {SCHEME} alone does not make a value a hash: a cleartext password can
// start with braces, and slapd cannot authenticate against an unrecognised scheme
// (tested: neither the intended password nor the literal string binds). So a value
// is only passed through when its scheme is one OpenLDAP recognises (core plus the
// pw-sha2, pw-pbkdf2 and pw-argon2 contrib modules).
var KNOWN_HASH_SCHEMES = ['{SSHA}', '{SHA}', '{SMD5}', '{MD5}', '{CRYPT}',
  '{SSHA256}', '{SSHA384}', '{SSHA512}', '{SHA256}', '{SHA384}', '{SHA512}',
  '{PBKDF2}', '{PBKDF2-SHA1}', '{PBKDF2-SHA256}', '{PBKDF2-SHA512}', '{ARGON2}'];

// IBM Verify Directory's reversible schemes. An admin read returns cleartext, so
// seeing one means the value was not decrypted; OpenLDAP cannot verify it.
var UNUSABLE_SCHEMES = ['{AES128}', '{AES192}', '{AES256}'];

// Returns 'hash' | 'unusable' | 'cleartext'.
function pwKind(value) {
  var s = pwScheme(value);
  if (s == null) return 'cleartext';
  var i;
  for (i = 0; i < KNOWN_HASH_SCHEMES.length; i++) if (KNOWN_HASH_SCHEMES[i] === s) return 'hash';
  for (i = 0; i < UNUSABLE_SCHEMES.length; i++) if (UNUSABLE_SCHEMES[i] === s) return 'unusable';
  return 'cleartext';
}

function migratePassword(work) {
  var raw = null;
  if (work.getAttribute) {
    var a = work.getAttribute('userPassword');
    if (a != null && a.getValue) {
      raw = (a.size && a.size() === 0) ? null : a.getValue(0);
    }
  }
  if (raw == null && work.getString) {
    raw = work.getString('userPassword');
  }
  var pw = pwValueToString(raw);
  if (pw == null || pw.length === 0) return null;

  var kind = pwKind(pw);
  if (kind === 'hash') return pw;                 // recognised hash: write verbatim, never re-hash
  if (kind === 'unusable') {                      // {AES...}: the source did not decrypt it
    if (typeof logmsg === 'function') {
      logmsg('WARN', 'userPassword is an undecrypted ' + pwScheme(pw) +
        ' value that OpenLDAP cannot verify; no password written. The source bind must be able to read decrypted passwords (e.g. cn=root).');
    }
    return null;
  }
  return hashSSHA(pw);                            // cleartext (incl. text that merely starts with braces)
}

function filterClasses(sourceClasses, dropList) {
  var out = [];
  for (var i = 0; i < sourceClasses.length; i++) {
    if (!isDropped(sourceClasses[i], dropList)) out.push(sourceClasses[i]);
  }
  return out;
}

function targetObjectClasses(work, mode, curatedClasses, dropList, includeClasses) {
  if (mode === 'mirror') {
    var oc = work.getAttribute('objectClass');
    var vals = oc ? oc.getValues() : [];
    return filterClasses(vals, dropList);
  }
  var extras = includeClasses || [];
  var out = curatedClasses.slice();
  var seen = {};
  for (var i = 0; i < out.length; i++) seen[String(out[i]).toLowerCase()] = true;
  for (var j = 0; j < extras.length; j++) {
    var key = String(extras[j]).toLowerCase();
    if (seen[key]) continue;
    seen[key] = true;
    out.push(extras[j]);
  }
  return out;
}

function passThroughAttrs(work, conn, dropList) {
  var names = work.getAttributeNames();
  for (var i = 0; i < names.length; i++) {
    var n = names[i];
    var lower = String(n).toLowerCase();
    if (lower === 'userpassword' || lower === 'objectclass') continue;
    if (isDropped(n, dropList)) continue;
    var a = work.getAttribute(n);
    if (a) conn.setAttribute(n, a.getValues());
  }
}

function includeNamedAttrs(work, conn, names) {
  for (var i = 0; i < names.length; i++) {
    var n = names[i];
    if (n == null || String(n).length === 0) continue;
    if (String(n).toLowerCase() === 'userpassword') continue;
    var a = work.getAttribute(n);
    if (a) conn.setAttribute(n, a.getValues());
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { pwScheme: pwScheme, parseList: parseList, isDropped: isDropped, DEFAULT_DROP_LIST: DEFAULT_DROP_LIST, _b64manual: _b64manual, sshaEncode: sshaEncode, hashSSHA: hashSSHA, pwValueToString: pwValueToString, pwKind: pwKind, migratePassword: migratePassword, filterClasses: filterClasses, targetObjectClasses: targetObjectClasses, passThroughAttrs: passThroughAttrs, includeNamedAttrs: includeNamedAttrs };
}
