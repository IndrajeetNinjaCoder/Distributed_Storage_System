/*
Load test for the Distributed File Storage Service.

Usage:
  k6 run load-test.js
  k6 run --vus 10 --duration 60s load-test.js
  k6 run -e CHUNK_SIZE=262144 -e FILE_SIZE=2097152 load-test.js

What each virtual user (VU) does, per iteration:
  1. POST /files            — get an upload plan
  2. PUT each chunk         — to its primary node (with replicas param, so real
                               replication happens, same as the Python client)
  3. POST /files/{id}/complete
  4. GET  /files/{id}       — get the download plan (now with presigned URLs
                               per replica, in order)
  5. GET  each chunk        — tries each replica URL in order (failover),
                               verifies the SHA-256 checksum matches
*/

import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Trend, Counter, Rate } from 'k6/metrics';

const METADATA_SERVICE = __ENV.METADATA_SERVICE || 'http://localhost:8090';
const CHUNK_SIZE = parseInt(__ENV.CHUNK_SIZE || '262144');   // 256 KB
const FILE_SIZE = parseInt(__ENV.FILE_SIZE || '1048576');    // 1 MB -> 4 chunks

// Custom metrics — these are what you'll pull numbers from for the README
const uploadDuration = new Trend('upload_duration_ms');
const downloadDuration = new Trend('download_duration_ms');
const chunkFailoverCount = new Counter('chunk_failover_used');
const checksumMismatchRate = new Rate('checksum_mismatch_rate');

export const options = {
  scenarios: {
    steady_load: {
      executor: 'constant-vus',
      vus: __ENV.VUS ? parseInt(__ENV.VUS) : 5,
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],      // less than 5% hard HTTP failures
    upload_duration_ms: ['p(95)<5000'],  // 95% of uploads under 5s
    download_duration_ms: ['p(95)<3000'],
  },
};

function randomBytes(size) {
  // Building a large string with += one char at a time is O(n^2) and far
  // too slow for MB-sized payloads in k6's JS engine. Instead, generate one
  // small random block and repeat it to the target size via array + join,
  // which is O(n). Content doesn't need true per-byte randomness — we only
  // care about checksum correctness and realistic transfer size.
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const blockSize = 1024;
  let block = '';
  for (let i = 0; i < blockSize; i++) {
    block += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  const repeats = Math.ceil(size / blockSize);
  const parts = new Array(repeats).fill(block);
  return parts.join('').substring(0, size);
}

export default function () {
  const fileName = `loadtest-${__VU}-${__ITER}.bin`;
  const fileData = randomBytes(FILE_SIZE);

  // ---- 1. Initiate upload ----
  const initRes = http.post(
    `${METADATA_SERVICE}/files`,
    JSON.stringify({ fileName, totalSize: FILE_SIZE, chunkSize: CHUNK_SIZE }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
  );

  if (!check(initRes, { 'upload plan created': (r) => r.status === 200 })) {
    return;
  }

  const plan = initRes.json();
  const fileId = plan.fileId;
  const chunks = plan.chunks;

  const uploadStart = Date.now();
  let uploadOk = true;

  // ---- 2. Upload each chunk to its primary, with replicas ----
  for (const chunk of chunks) {
    const start = (chunk.chunkIndex) * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, FILE_SIZE);
    const data = fileData.substring(start, end);

    const replicasParam = (chunk.replicaUrls || [chunk.storageNodeUrl]).join(',');
    const putUrl = `${chunk.storageNodeUrl}/chunks/${chunk.chunkId}?replicas=${replicasParam}`;

    const putRes = http.put(putUrl, data);
    if (!check(putRes, { 'chunk uploaded': (r) => r.status === 200 })) {
      uploadOk = false;
      continue;
    }

    const checksum = putRes.body.trim();
    http.patch(
      `${METADATA_SERVICE}/files/${fileId}/chunks/${chunk.chunkId}/checksum`,
      JSON.stringify({ checksum }),
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  // ---- 3. Mark complete ----
  http.post(`${METADATA_SERVICE}/files/${fileId}/complete`);
  uploadDuration.add(Date.now() - uploadStart);

  if (!uploadOk) {
    return; // don't bother downloading a partially-failed upload
  }

  // ---- 4. Get download plan ----
  const downloadStart = Date.now();
  const infoRes = http.get(`${METADATA_SERVICE}/files/${fileId}`);
  if (!check(infoRes, { 'download plan fetched': (r) => r.status === 200 })) {
    return;
  }
  const info = infoRes.json();
  const dlChunks = info.chunks.sort((a, b) => a.chunkIndex - b.chunkIndex);

  // ---- 5. Download each chunk, trying replicas in order ----
  for (const chunk of dlChunks) {
    const urls = chunk.presignedUrls || [chunk.storageNodeUrl];
    let got = null;
    let triedCount = 0;

    for (const url of urls) {
      triedCount++;
      const r = http.get(url);
      if (r.status === 200) {
        got = r;
        break;
      }
    }

    if (triedCount > 1 && got) {
      chunkFailoverCount.add(1); // primary failed, a fallback replica served it
    }

    check(got, { 'chunk downloaded': (r) => r !== null });

    if (got && chunk.checksum) {
      const actualChecksum = crypto.sha256(got.body, 'hex');
      const matched = actualChecksum === chunk.checksum;
      checksumMismatchRate.add(!matched);
      check(matched, { 'checksum matches': () => matched });
    }
  }

  downloadDuration.add(Date.now() - downloadStart);

  sleep(1); // brief pause between iterations per VU
}