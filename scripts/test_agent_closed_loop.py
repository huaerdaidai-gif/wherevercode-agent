#!/usr/bin/env python3
import socket, json, hashlib, os, time, base64, subprocess, hmac, sys

MAC_HOST = os.environ.get('WC_TEST_MAC_HOST', '')
MAC_PORT = 18743
MAC_USER = os.environ.get('WC_TEST_MAC_USER', 'user')
SSH_KEY = os.environ.get('WC_TEST_SSH_KEY', '~/.ssh/id_ed25519')
HAND_DIR = os.environ.get('WC_TEST_HAND_DIR', f'/Users/{MAC_USER}/Library/Application Support/WhereverCode/V5Hand')

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)

def derive_session(cli_priv, cli_pub, srv_pub):
    shared = cli_priv.exchange(X25519PublicKey.from_public_bytes(srv_pub))
    first, second = (cli_pub, srv_pub) if cli_pub <= srv_pub else (srv_pub, cli_pub)
    salt = hashlib.sha256(first + second).digest()
    return hkdf_sha256(shared, salt, b'wherevercode-cross-device/v1', 32)

def derive_signing(session_key):
    salt = hashlib.sha256(b'wherevercode-credential-signing/v1').digest()
    return hkdf_sha256(session_key, salt, b'wherevercode-credential-signing/v1', 32)

def read_frame(s):
    hdr = b''
    while len(hdr) < 4:
        chunk = s.recv(4 - len(hdr))
        if not chunk: raise ConnectionError('远端过早关闭连接 (EOF)')
        hdr += chunk
    l = int.from_bytes(hdr, 'big')
    buf = b''
    while len(buf) < l:
        chunk = s.recv(l - len(buf))
        if not chunk: raise ConnectionError('远端过早关闭连接 (EOF)')
        buf += chunk
    return buf

def write_frame(s, payload):
    s.sendall(len(payload).to_bytes(4, 'big') + payload)

def seal(key, plain):
    nonce = os.urandom(12)
    return nonce + AESGCM(key).encrypt(nonce, plain, None)

def open_sealed(key, sealed):
    return AESGCM(key).decrypt(sealed[:12], sealed[12:], None)

def make_credential(signing_key, client_id, target_device, agent_id, folder, task_id):
    now_ts = int(time.time())
    jti = str(os.urandom(8).hex())
    exp_ts = now_ts + 900
    fields = ['', task_id, '1', client_id, target_device, agent_id, folder, str(now_ts), str(exp_ts), jti, agent_id, folder, '1', '1']
    canonical_payload = '|'.join(fields).encode('utf-8')
    mac_key = hashlib.sha256(signing_key).digest()
    sig_b64 = base64.b64encode(hmac.new(mac_key, canonical_payload, hashlib.sha256).digest()).decode()
    cred_obj = {
        'v': 2, 'user_id': '', 'task_id': task_id, 'attempt': '1',
        'source_device_id': client_id, 'target_device_id': target_device,
        'agent_id': agent_id, 'folder': folder, 'iat': now_ts, 'exp': exp_ts,
        'jti': jti, 'scope': {'agent_id': agent_id, 'folder': folder},
        'epoch': 1, 'goal_version': 1, 'sig_b64': sig_b64
    }
    return base64.urlsafe_b64encode(json.dumps(cred_obj).encode()).decode().rstrip('=')

def execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, action, call_args):
    """Executes a single tool call exactly as HandClient.swift does."""
    c_pub = c_priv.public_key().public_bytes_raw()
    s = socket.create_connection((MAC_HOST, MAC_PORT), timeout=10)
    try:
        # 1. Hello
        hello = {'type': 'wherevercode_hello', 'version': 1, 'device_id': client_id, 'public_key_b64': base64.b64encode(c_pub).decode()}
        write_frame(s, json.dumps(hello).encode())
        srv_hello = json.loads(read_frame(s))
        srv_pub = base64.b64decode(srv_hello['public_key_b64'])
        session_key = derive_session(c_priv, c_pub, srv_pub)
        signing_key = derive_signing(session_key)

        cred = make_credential(signing_key, client_id, target_dev, agent_id, folder, task_id)
        args_with_cred = dict(call_args)
        args_with_cred['credential'] = cred

        call_env = {
            'type': 'cross_device_tool_call', 'version': 1, 'seq': 1,
            'task_id': task_id, 'agent_id': agent_id, 'source_device_id': client_id,
            'target_device_id': target_dev, 'folder': folder, 'action': action,
            'args': args_with_cred
        }
        write_frame(s, seal(session_key, json.dumps(call_env).encode()))
        
        # Read reply (accumulate progress if any)
        progress_messages = []
        while True:
            raw_reply = read_frame(s)
            resp = json.loads(open_sealed(session_key, raw_reply))
            rtype = resp.get('type')
            if rtype == 'cross_device_progress':
                progress_messages.append(resp.get('args', {}).get('message', ''))
            else:
                resp['progress'] = progress_messages
                return resp
    finally:
        s.close()

def main():
    print("=== WHEREVERCODE V5: IPHONE + MAC + SINGLE AGENT CLOSED-LOOP ACCEPTANCE ===")
    
    # 0. Rotate a fresh pair code on Mac Dev Host
    print("[0/6] Rotating fresh pairing code via SSH on Mac host...")
    out = subprocess.check_output([
        'ssh', '-i', os.path.expanduser(SSH_KEY), f'{MAC_USER}@{MAC_HOST}',
        f"'{HAND_DIR}/wherevercode-hand' pair-code --rotate --raw"
    ]).decode().strip()
    pair_code = out.split()[0]
    print(f"      Rotated Pair Code: {pair_code}")

    # 1. Probe Hello
    print("[1/6] Probing Mac Hand server (Hello exchange)...")
    c_priv = X25519PrivateKey.generate()
    c_pub = c_priv.public_key().public_bytes_raw()
    client_id = 'iphone-p0-verify-' + base64.b32encode(c_pub[:5]).decode().lower()

    s = socket.create_connection((MAC_HOST, MAC_PORT), timeout=5)
    hello = {'type': 'wherevercode_hello', 'version': 1, 'device_id': client_id, 'public_key_b64': base64.b64encode(c_pub).decode()}
    write_frame(s, json.dumps(hello).encode())
    srv_hello = json.loads(read_frame(s))
    srv_pub = base64.b64decode(srv_hello['public_key_b64'])
    print(f"      Remote Device ID: {srv_hello.get('device_id')}")
    print(f"      Remote Display Name: {srv_hello.get('display_name')}")
    print(f"      Remote Version: {srv_hello.get('version')}")
    print(f"      Remote Agents: {[a.get('agent_id') for a in srv_hello.get('agents', [])]}")
    
    # Check folder announcement is real user directory (no example-user)
    for ag in srv_hello.get('agents', []):
        assert 'example-user' not in ag.get('folder', ''), f"Found fake user in folder: {ag.get('folder')}"
    print("      -> Announce verification PASS: no example-user found in announced folders")

    # 2. Pair confirmation
    print(f"[2/6] Executing pair confirm using code {pair_code}...")
    session_key = derive_session(c_priv, c_pub, srv_pub)
    confirm = {'type': 'cross_device_pair_confirm', 'version': 1, 'code': pair_code}
    write_frame(s, seal(session_key, json.dumps(confirm).encode()))
    reply = json.loads(open_sealed(session_key, read_frame(s)))
    print(f"      Pair confirm reply: {reply.get('type')}")
    assert reply.get('type') == 'cross_device_pair_approved', f"Pair failed: {reply}"
    s.close()
    print("      -> Pairing verified: PASS")

    folder = os.environ.get('WC_TEST_WORK_FOLDER', f'/Users/{MAC_USER}/v5-codex-work')
    target_dev = srv_hello.get('device_id', 'MacBook-Air')
    agent_id = 'codex'
    task_id = 'task-closedloop-' + base64.b32encode(os.urandom(4)).decode().lower()

    # Pre-grant folder for codex
    subprocess.run([
        'ssh', '-i', os.path.expanduser(SSH_KEY), f'{MAC_USER}@{MAC_HOST}',
        f"'{HAND_DIR}/wherevercode-hand' grant {agent_id} '{folder}'"
    ], check=True, stdout=subprocess.DEVNULL)

    # 3. Cross-Device Tool Operations: list, write, read
    print(f"[3/6] Executing cross-device storage operations in {folder}...")
    
    # 3a. listDirectory
    list_res = execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, 'list', {'path': ''})
    assert list_res.get('type') == 'cross_device_result', f"List failed: {list_res}"
    print(f"      listDirectory entries: {list_res.get('args', {}).get('output')}")

    # 3b. writeFile
    proof_content = f"V5 iOS Single Agent Closed-Loop Acceptance Proof at {time.strftime('%Y-%m-%d %H:%M:%S')}"
    write_res = execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, 'write', {'path': 'proof_closed_loop.txt', 'content': proof_content})
    assert write_res.get('type') == 'cross_device_result', f"Write failed: {write_res}"
    print(f"      writeFile result: {write_res.get('args', {}).get('output')}")

    # 3c. readFile
    read_res = execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, 'read', {'path': 'proof_closed_loop.txt'})
    assert read_res.get('type') == 'cross_device_result', f"Read failed: {read_res}"
    read_text = read_res.get('args', {}).get('output', '')
    assert read_text == proof_content, f"Content mismatch: expected {proof_content}, got {read_text}"
    print(f"      readFile verified content: \"{read_text}\"")

    # 4. Folder Authorization API Test (No wrong_folder!)
    print("[4/6] Testing authorize_project_folder API...")
    auth_res = execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, 'authorize_project_folder', {'folder': folder})
    print(f"      authorize_project_folder reply: {auth_res.get('type')}")
    assert auth_res.get('type') == 'cross_device_result', f"Authorization failed: {auth_res}"
    print("      -> Folder authorization verified: PASS (no wrong_folder)")

    # 5. Remote Single Agent Control Signals (stop/status)
    print("[5/6] Testing single agent remote control channel (__control__ stop)...")
    stop_res = execute_call(c_priv, client_id, target_dev, agent_id, folder, task_id, 'run', {
        'argv': json.dumps(['__control__', 'stop', 'op-closed-loop-1']),
        'control': json.dumps({'epoch': 1, 'goal_version': 1})
    })
    print(f"      __control__ stop result: {stop_res.get('type')}")
    assert stop_res.get('type') in ('cross_device_result', 'cross_device_progress'), f"Control stop failed: {stop_res}"
    print("      -> Single-agent control signal verified: PASS")

    # 6. Clean up temporary test client from paired_clients.json
    print("[6/6] Cleaning up test client credentials on Mac dev host...")
    subprocess.run([
        'ssh', '-i', os.path.expanduser(SSH_KEY), f'{MAC_USER}@{MAC_HOST}',
        f'python3 -c "import json; path=\\\"{HAND_DIR}/paired_clients.json\\\"; d=json.load(open(path)); d.pop(\\\"{client_id}\\\", None); json.dump(d, open(path,\\\"w\\\"), indent=2)"'
    ], check=True)

    print("\n=======================================================")
    print(">>> 1 IPHONE + 1 MAC + 1 AGENT CLOSED-LOOP ACCEPTED <<<")
    print("=======================================================")
    return 0

if __name__ == '__main__':
    sys.exit(main())
