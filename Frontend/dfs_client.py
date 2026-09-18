"""
Distributed File Storage Service — simple client.

Usage:
    python dfs_client.py upload <path-to-file> [--chunk-size 262144]
    python dfs_client.py download <fileId> <output-path>

Requires: pip install requests
"""

import sys
import os
import requests

METADATA_SERVICE = "http://localhost:8090"
DEFAULT_CHUNK_SIZE = 256 * 1024  # 256 KB — adjust as you like


def upload(file_path: str, chunk_size: int = DEFAULT_CHUNK_SIZE):
    if not os.path.isfile(file_path):
        print(f"File not found: {file_path}")
        sys.exit(1)

    file_name = os.path.basename(file_path)
    total_size = os.path.getsize(file_path)

    print(f"Uploading '{file_name}' ({total_size} bytes, chunk size {chunk_size})...")

    # 1. Ask Metadata Service for the upload plan
    resp = requests.post(f"{METADATA_SERVICE}/files", json={
        "fileName": file_name,
        "totalSize": total_size,
        "chunkSize": chunk_size,
    })
    resp.raise_for_status()
    plan = resp.json()
    file_id = plan["fileId"]
    chunks = plan["chunks"]

    print(f"Got fileId: {file_id} — {len(chunks)} chunk(s) planned")

    # 2. Read the file and upload each chunk to its primary node
    with open(file_path, "rb") as f:
        for chunk in chunks:
            data = f.read(chunk_size)
            chunk_id = chunk["chunkId"]
            primary_url = chunk["storageNodeUrl"]
            replica_urls = chunk.get("replicaUrls", [primary_url])

            replicas_param = ",".join(replica_urls)
            put_url = f"{primary_url}/chunks/{chunk_id}?replicas={replicas_param}"

            r = requests.put(put_url, data=data)
            r.raise_for_status()
            print(f"  Chunk {chunk['chunkIndex']} ({len(data)} bytes) -> {primary_url}  OK")

    # 3. Mark the upload complete
    r = requests.post(f"{METADATA_SERVICE}/files/{file_id}/complete")
    r.raise_for_status()

    print(f"\nUpload complete. fileId = {file_id}")
    print(f"Download it later with:\n  python dfs_client.py download {file_id} <output-path>")


def download(file_id: str, output_path: str):
    print(f"Fetching chunk plan for fileId {file_id}...")

    resp = requests.get(f"{METADATA_SERVICE}/files/{file_id}")
    resp.raise_for_status()
    info = resp.json()
    chunks = sorted(info["chunks"], key=lambda c: c["chunkIndex"])

    print(f"File: {info['fileName']}  Status: {info['status']}  Chunks: {len(chunks)}")

    with open(output_path, "wb") as out:
        for chunk in chunks:
            chunk_id = chunk["chunkId"]
            primary_url = chunk["storageNodeUrl"]

            r = requests.get(f"{primary_url}/chunks/{chunk_id}")
            r.raise_for_status()
            out.write(r.content)
            print(f"  Chunk {chunk['chunkIndex']} ({len(r.content)} bytes) <- {primary_url}  OK")

    print(f"\nDownload complete: {output_path}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1]

    if command == "upload":
        file_path = sys.argv[2]
        chunk_size = DEFAULT_CHUNK_SIZE
        if "--chunk-size" in sys.argv:
            idx = sys.argv.index("--chunk-size")
            chunk_size = int(sys.argv[idx + 1])
        upload(file_path, chunk_size)

    elif command == "download":
        if len(sys.argv) < 4:
            print("Usage: python dfs_client.py download <fileId> <output-path>")
            sys.exit(1)
        file_id = sys.argv[2]
        output_path = sys.argv[3]
        download(file_id, output_path)

    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
