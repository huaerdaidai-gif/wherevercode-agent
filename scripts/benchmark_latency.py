#!/usr/bin/env python3
import socket, json, hashlib, os, time, base64, subprocess, hmac, sys

MAC_HOST = '100.75.41.0'
MAC_PORT = 18743

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

def main():
    measurements = {}
    
    # 1. Rotate Pair Code
    t0 = time.perf_counter()
    out = subprocess.check_output([
        'ssh', '-i', os.path.expanduser('~/.ssh/infra_ed25519'), 'a1234@100.75.41.0',
        "'/Users/a1234/Library/Application Support/WhereverCode/V5Hand/wherevercode-hand' pair-code --rotate --raw"
    ]).decode().strip()
    pair_code = out.split()[0]
    measurements['ssh_rotate_pair_code_ms'] = round((time.perf_counter() - t0) * 1000, 2)

    c_priv = X25519PrivateKey.generate()
    c_pub = c_priv.public_key().public_bytes_raw()
    client_id = 'bench-' + base64.b32encode(c_pub[:5]).decode().lower()

    # 2. Handshake & Pairing
    t0 = time.perf_counter()
    s = socket.create_connection((MAC_HOST, MAC_PORT), timeout=5)
    t_conn = time.perf_counter()
    hello = {'type': 'wherevercode_hello', 'version': 1, 'device_id': client_id, 'public_key_b64': base64.b64encode(c_pub).decode()}
    write_frame(s, json.dumps(hello).encode())
    srv_hello = json.loads(read_frame(s))
    t_hello = time.perf_counter()

    srv_pub = base64.b64decode(srv_hello['public_key_b64'])
    session_key = derive_session(c_priv, c_pub, srv_pub)
    confirm = {'type': 'cross_device_pair_confirm', 'version': 1, 'code': pair_code}
    write_frame(s, seal(session_key, json.dumps(confirm).encode()))
    reply = json.loads(open_sealed(session_key, read_frame(s)))
    t_pair = time.perf_counter()
    s.close()

    measurements['tcp_connect_ms'] = round((t_conn - t0) * 1000, 2)
    measurements['hello_rtt_ms'] = round((t_hello - t_conn) * 1000, 2)
    measurements['pair_confirm_rtt_ms'] = round((t_pair - t_hello) * 1000, 2)

    folder = '/Users/a1234/v5-codex-work'
    target_dev = 'MacBook-Air'
    agent_id = 'codex'
    task_id = 'task-bench-' + str(int(time.time()))

    def benchmark_call(action, call_args):
        c_pub = c_priv.public_key().public_bytes_raw()
        t_start = time.perf_counter()
        s = socket.create_connection((MAC_HOST, MAC_PORT), timeout=10)
        t_sock = time.perf_counter()
        hello = {'type': 'wherevercode_hello', 'version': 1, 'device_id': client_id, 'public_key_b64': base64.b64encode(c_pub).decode()}
        write_frame(s, json.dumps(hello).encode())
        srv_hello = json.loads(read_frame(s))
        t_handshake = time.perf_counter()
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
        t_send = time.perf_counter()
        write_frame(s, seal(session_key, json.dumps(call_env).encode()))
        
        raw_reply = read_frame(s)
        t_recv = time.perf_counter()
        resp = json.loads(open_sealed(session_key, raw_reply))
        s.close()
        t_end = time.perf_counter()
        
        return {
            'tcp_connect_ms': round((t_sock - t_start) * 1000, 2),
            'hello_handshake_ms': round((t_handshake - t_sock) * 1000, 2),
            'ttft_ms': round((t_recv - t_send) * 1000, 2),
            'total_call_ms': round((t_end - t_start) * 1000, 2),
            'status': resp.get('type')
        }

    measurements['tool_list_files'] = benchmark_call('list', {'path': ''})
    measurements['tool_write_file'] = benchmark_call('write', {'path': 'bench_perf.txt', 'content': 'Benchmark test ' + str(time.time())})
    measurements['tool_read_file'] = benchmark_call('read', {'path': 'bench_perf.txt'})
    measurements['tool_authorize_folder'] = benchmark_call('authorize_project_folder', {'folder': folder})
    measurements['tool_control_stop'] = benchmark_call('run', {
        'argv': json.dumps(['__control__', 'stop', 'op-bench']),
        'control': json.dumps({'epoch': 1, 'goal_version': 1})
    })

    # Cleanup
    clean_cmd = f"python3 -c 'import json; path=\"/Users/a1234/Library/Application Support/WhereverCode/V5Hand/paired_clients.json\"; d=json.load(open(path)); d.pop(\"{client_id}\", None); json.dump(d, open(path, \"w\"), indent=2)'"
    subprocess.run([
        'ssh', '-i', os.path.expanduser('~/.ssh/infra_ed25519'), 'a1234@100.75.41.0',
        clean_cmd
    ], check=True)

    print("--- BENCHMARK RESULTS (JSON) ---")
    print(json.dumps(measurements, indent=2, ensure_ascii=False))

if __name__ == '__main__':
    main()
