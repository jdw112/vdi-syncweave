// examples/FDS_OpenLDAP_Target/test/helpers.test.js
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const h = require('../maps/customScript-additions.js');

test('pwScheme detects prefixed schemes and cleartext', () => {
  assert.strictEqual(h.pwScheme('{SSHA}abc'), '{SSHA}');
  assert.strictEqual(h.pwScheme('{ssha256}abc'), '{SSHA256}');
  assert.strictEqual(h.pwScheme('P@ssw0rd'), null);
  assert.strictEqual(h.pwScheme(''), null);
  assert.strictEqual(h.pwScheme(null), null);
});

test('parseList normalizes csv', () => {
  assert.deepStrictEqual(h.parseList('A, b ,,C'), ['a', 'b', 'c']);
  assert.deepStrictEqual(h.parseList(''), []);
});

test('isDropped honors exact, prefix*, *contains*, and always-keep', () => {
  const dl = ['createtimestamp', 'ibm-slapd*', '*password*'];
  assert.strictEqual(h.isDropped('createTimestamp', dl), true);
  assert.strictEqual(h.isDropped('ibm-slapdisconfigurationonly', dl), true);
  assert.strictEqual(h.isDropped('erpassword', dl), true);
  assert.strictEqual(h.isDropped('cn', dl), false);
  assert.strictEqual(h.isDropped('userPassword', dl), false); // always kept
  assert.strictEqual(h.isDropped('adDn', dl), false);         // always kept
  assert.strictEqual(h.isDropped('createTimestamp'), true);   // default fallback
  assert.strictEqual(h.isDropped('IBM-SLAPDfoo', ['IBM-SLAPD*']), true); // mixed-case pattern
});

test('DEFAULT_DROP_LIST is lowercased and includes key names', () => {
  assert.deepStrictEqual(h.DEFAULT_DROP_LIST, ['createtimestamp','modifytimestamp','creatorsname','modifiersname','entryuuid','hassubordinates','subschemasubentry','aclentry','aclpropagate','entryowner','ibm-entryuuid','ibm-slapd*','secretkey','*password*']);
});

test('_b64manual encodes bytes to standard base64 (production SDI path)', () => {
  assert.strictEqual(h._b64manual([]), '');
  assert.strictEqual(h._b64manual([77]), 'TQ==');       // "M"
  assert.strictEqual(h._b64manual([77,97]), 'TWE=');    // "Ma"
  assert.strictEqual(h._b64manual([77,97,110]), 'TWFu'); // "Man"
  const bytes = [0,1,2,250,251,252,253,254,255];
  assert.strictEqual(h._b64manual(bytes), Buffer.from(bytes).toString('base64'));
});

test('sshaEncode assembles {SSHA} + base64(digest+salt)', () => {
  const salt = Buffer.from('12345678');
  const digest = crypto.createHash('sha1').update(Buffer.concat([Buffer.from('secret'), salt])).digest();
  const out = h.sshaEncode(Array.from(digest), Array.from(salt));
  assert.ok(out.startsWith('{SSHA}'));
  // round-trip: decode, split, recompute
  const raw = Buffer.from(out.slice(6), 'base64');
  const recoveredSalt = raw.slice(20); // sha1 digest is 20 bytes
  const recomputed = crypto.createHash('sha1').update(Buffer.concat([Buffer.from('secret'), recoveredSalt])).digest();
  assert.strictEqual(Buffer.compare(recomputed, raw.slice(0, 20)), 0);
});

test('migratePassword passes hashed values verbatim, hashes cleartext', () => {
  const hashedWork = { getString: (k) => k === 'userPassword' ? '{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA' : null };
  assert.strictEqual(h.migratePassword(hashedWork), '{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA');

  const clearWork = { getString: (k) => k === 'userPassword' ? 'P@ssw0rd' : null };
  const out = h.migratePassword(clearWork);
  assert.ok(out.startsWith('{SSHA}')); // cleartext got hashed

  const emptyWork = { getString: () => null };
  assert.strictEqual(h.migratePassword(emptyWork), null);

  // getAttribute path (how it reads in SDI): value may be a String
  const attrWork = { getAttribute: (k) => k === 'userPassword'
    ? { size: () => 1, getValue: () => '{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA' } : null };
  assert.strictEqual(h.migratePassword(attrWork), '{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA');

  // empty attribute -> null
  const emptyAttrWork = { getAttribute: () => ({ size: () => 0, getValue: () => null }) };
  assert.strictEqual(h.migratePassword(emptyAttrWork), null);
});

test('pwValueToString handles strings and stringifiable values', () => {
  assert.strictEqual(h.pwValueToString('{SSHA}abc'), '{SSHA}abc');
  assert.strictEqual(h.pwValueToString(null), null);
  assert.strictEqual(h.pwValueToString({ toString: () => '{SHA}z' }), '{SHA}z');
});

function fakeEntry(attrs) {
  return {
    _a: attrs,
    getAttributeNames: () => Object.keys(attrs),
    getAttribute: (n) => attrs[n] ? { getValues: () => attrs[n] } : null,
    setAttribute: function (n, v) { this._a[n] = v; }
  };
}

test('filterClasses removes dropped classes', () => {
  assert.deepStrictEqual(
    h.filterClasses(['inetOrgPerson', 'erPersonItem'], ['erpersonitem']),
    ['inetOrgPerson']
  );
});

test('targetObjectClasses: curated returns fixed set, mirror passes source through', () => {
  const work = fakeEntry({ objectClass: ['top', 'inetOrgPerson', 'erPersonItem'] });
  assert.deepStrictEqual(
    h.targetObjectClasses(work, 'curated', ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata'], []),
    ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata']
  );
  assert.deepStrictEqual(
    h.targetObjectClasses(work, 'mirror', null, ['erpersonitem']),
    ['top', 'inetOrgPerson']
  );
});

test('targetObjectClasses: curated backward-compatible when includeClasses is omitted', () => {
  const work = fakeEntry({ objectClass: ['top', 'inetOrgPerson'] });
  assert.deepStrictEqual(
    h.targetObjectClasses(work, 'curated', ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata']),
    ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata']
  );
});

test('targetObjectClasses: curated appends includeClasses, de-duplicated case-insensitively, order preserved', () => {
  const work = fakeEntry({ objectClass: ['top', 'inetOrgPerson'] });
  assert.deepStrictEqual(
    h.targetObjectClasses(
      work, 'curated',
      ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata'],
      [],
      ['erPersonItem', 'FDSSYNCMETADATA', 'top', 'igiRole']
    ),
    ['top', 'person', 'inetOrgPerson', 'fdsSyncMetadata', 'erPersonItem', 'igiRole']
  );
});

test('targetObjectClasses: mirror mode ignores includeClasses', () => {
  const work = fakeEntry({ objectClass: ['top', 'inetOrgPerson', 'erPersonItem'] });
  assert.deepStrictEqual(
    h.targetObjectClasses(work, 'mirror', null, ['erpersonitem'], ['igiRole']),
    ['top', 'inetOrgPerson']
  );
});

test('passThroughAttrs copies non-dropped attrs, never userPassword, never objectClass', () => {
  const work = fakeEntry({ cn: ['Jo'], erglobalid: ['x'], userPassword: ['{SSHA}z'], adDn: ['uid=jo,o=src'], objectClass: ['top', 'inetOrgPerson'] });
  const conn = fakeEntry({});
  h.passThroughAttrs(work, conn, ['erglobalid']);
  assert.deepStrictEqual(conn._a.cn, ['Jo']);
  assert.deepStrictEqual(conn._a.adDn, ['uid=jo,o=src']); // always kept
  assert.strictEqual(conn._a.erglobalid, undefined);      // dropped
  assert.strictEqual(conn._a.userPassword, undefined);    // never via pass-through
  assert.strictEqual(conn._a.objectClass, undefined);     // targetObjectClasses is the sole source of truth
});

test('includeNamedAttrs copies listed attrs from work to conn', () => {
  const work = fakeEntry({ cn: ['Jo'], mail: ['jo@example.com'], erglobalid: ['x'] });
  const conn = fakeEntry({});
  h.includeNamedAttrs(work, conn, ['mail', 'erglobalid']);
  assert.deepStrictEqual(conn._a.mail, ['jo@example.com']);
  assert.deepStrictEqual(conn._a.erglobalid, ['x']);
  assert.strictEqual(conn._a.cn, undefined); // not requested
});

test('includeNamedAttrs skips userPassword case-insensitively even if named', () => {
  const work = fakeEntry({ userPassword: ['{SSHA}z'], mail: ['jo@example.com'] });
  const conn = fakeEntry({});
  h.includeNamedAttrs(work, conn, ['userPassword', 'USERPASSWORD', 'mail']);
  assert.strictEqual(conn._a.userPassword, undefined);
  assert.strictEqual(conn._a.USERPASSWORD, undefined);
  assert.deepStrictEqual(conn._a.mail, ['jo@example.com']);
});

test('includeNamedAttrs skips names absent on work and empty names', () => {
  const work = fakeEntry({ mail: ['jo@example.com'] });
  const conn = fakeEntry({});
  h.includeNamedAttrs(work, conn, ['', 'nonexistentAttr', 'mail']);
  assert.deepStrictEqual(conn._a.mail, ['jo@example.com']);
  assert.strictEqual(conn._a.nonexistentAttr, undefined);
  assert.strictEqual(Object.prototype.hasOwnProperty.call(conn._a, ''), false);
});

test('includeNamedAttrs is a no-op on an empty list', () => {
  const work = fakeEntry({ mail: ['jo@example.com'] });
  const conn = fakeEntry({});
  h.includeNamedAttrs(work, conn, []);
  assert.deepStrictEqual(conn._a, {});
});

test('pwKind classifies hashes, undecrypted reversible schemes, and cleartext', () => {
  assert.strictEqual(h.pwKind('{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA'), 'hash');
  assert.strictEqual(h.pwKind('{ssha}abc'), 'hash');
  assert.strictEqual(h.pwKind('{SSHA256}abc'), 'hash');
  assert.strictEqual(h.pwKind('{PBKDF2-SHA256}10000$abc$def'), 'hash');
  assert.strictEqual(h.pwKind('{AES256}lFwcnnTEVaJbVkUeyYEW6A=='), 'unusable');
  assert.strictEqual(h.pwKind('{aes128}xyz'), 'unusable');
  assert.strictEqual(h.pwKind('{abc}secret'), 'cleartext');   // braces alone do not make a hash
  assert.strictEqual(h.pwKind('P@ssw0rd'), 'cleartext');
  assert.strictEqual(h.pwKind(''), 'cleartext');
});

test('migratePassword hashes brace-prefixed cleartext and drops undecrypted AES values', () => {
  const w = (v) => ({ getString: (k) => (k === 'userPassword' ? v : null) });

  // cleartext that merely starts with braces is a password, not a hash: hash it
  const out = h.migratePassword(w('{abc}secret'));
  assert.ok(out.startsWith('{SSHA}'));
  const raw = Buffer.from(out.slice(6), 'base64');
  const salt = raw.slice(20);
  const check = crypto.createHash('sha1').update(Buffer.concat([Buffer.from('{abc}secret'), salt])).digest();
  assert.strictEqual(Buffer.compare(check, raw.slice(0, 20)), 0);   // verifies against the original text

  // an {AES...} value means the source did not decrypt it; OpenLDAP cannot verify it: write nothing
  assert.strictEqual(h.migratePassword(w('{AES256}lFwcnnTEVaJbVkUeyYEW6A==')), null);

  // recognised hash schemes still pass through verbatim
  assert.strictEqual(h.migratePassword(w('{SSHA256}abc')), '{SSHA256}abc');
  assert.strictEqual(h.migratePassword(w('{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA')), '{SSHA}Ux2JMpFgVzNb3QkNjVl5W7eOqGtRxYzA');
});
