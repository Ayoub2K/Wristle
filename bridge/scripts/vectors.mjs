// Writes relay/test-vectors.json from fixed inputs, so the bridge and watch tests check the same bytes.
// Run after `npm run build`: node scripts/vectors.mjs
import { createHash } from "node:crypto";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { deriveSessionKeys, publicKeyOf, seal } from "../dist/crypto.js";

const fixed = (label, length = 32) => createHash("sha256").update(label).digest().subarray(0, length);
const b64 = (bytes) => Buffer.from(bytes).toString("base64");

const watchPrivate = fixed("wristle test watch key");
const bridgePrivate = fixed("wristle test bridge key");
const watchPublic = publicKeyOf(watchPrivate);
const bridgePublic = publicKeyOf(bridgePrivate);
const keys = deriveSessionKeys(bridgePrivate, watchPublic, watchPublic, bridgePublic);

const pairingId = "test-pairing";
const message = { type: "ping", ts: 1793600000000, id: "abc" };
const envelope = seal(keys.b2w, pairingId, "b2w", 7, message, fixed("wristle test nonce", 12));

const vectors = {
  watchPrivate: b64(watchPrivate),
  watchPublic: b64(watchPublic),
  bridgePrivate: b64(bridgePrivate),
  bridgePublic: b64(bridgePublic),
  w2bKey: b64(keys.w2b),
  b2wKey: b64(keys.b2w),
  sas: keys.sas,
  message: { pairingId, direction: "b2w", plaintext: JSON.stringify(message), envelope },
};
const target = fileURLToPath(new URL("../../relay/test-vectors.json", import.meta.url));
fs.writeFileSync(target, JSON.stringify(vectors, null, 2) + "\n");
console.log(`wrote ${target}`);
