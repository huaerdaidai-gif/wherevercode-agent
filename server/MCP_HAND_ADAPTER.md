# MCP → Hand device tools

`hand_mcp_stdio.py` is a vendor-neutral stdio MCP server for the existing Hand
v1 protocol. Any MCP-compatible Agent can start it and call these tools:

- `hand_devices`: inspect configured device IDs, pairing/reachability status,
  and available authorization IDs.
- `device_list`, `device_read`, `device_write`: select a configured `device_id`
  and `authorization_id`; paths remain relative to the grant's Hand folder.
- `device_run`: send only `pause`, `resume`, `stop`, or `change_goal` controls.
  It never launches an Agent process on the execution device.

MCP arguments cannot choose a host, port, filesystem root, credential, or raw
command. The registry binds each device ID to its endpoint and pinned Hand
public key, and binds each authorization ID to its `agent_id` and folder grant.
The target must also report the client as `APPROVED`, and its Hand server checks
the folder grant and task credential on each call. Unknown device IDs or grant
IDs are refused before connecting.

The adapter uses the existing Hand hello, X25519/HKDF session key, AES-256-GCM
sealed envelope, v2 task credential, and control snapshot. Each call receives a
fresh one-use credential. Offline, timeout, pairing, route, replay, and fence
errors fail the tool call without local filesystem or shell fallback. Hand
audit records contain operation metadata only.

## Install the Hand execution end

On each device that should provide tools, install Python 3.10+ and the
`cryptography` package in its existing Python environment, then copy this
repository's `server/` directory to that device. Check the device ID, public
key, and fingerprint:

```sh
cd server
python3 -m pip install --user -r requirements.txt
python3 hand_server.py --device-id hand-node-01 --display-name "Hand Node 01" public-key
python3 hand_server.py --device-id hand-node-01 --display-name "Hand Node 01" fingerprint
```

Add an explicit folder grant. The grant label is an authorization scope name;
it does not install or start an Agent:

```sh
python3 hand_server.py grant mcp-tools /path/to/authorized/folder
python3 hand_server.py --device-id hand-node-01 --display-name "Hand Node 01" serve --host 0.0.0.0 --port 18743
```

Keep the listener on a private network. Hand pairing and folder grants are
separate approvals.

## Create the MCP client identity

The client identity is shared across the compatible MCP host process and is
paired separately on each Hand device. Create it on the MCP host:

```sh
uv venv .venv --python python3
uv pip install --no-build --python .venv/bin/python -r server/requirements.txt
.venv/bin/python server/hand_mcp_stdio.py --init-identity ~/.config/wherevercode-agent/mcp-client-identity.json
```

The command prints a device ID, public key, and SHA-256 fingerprint. The
private identity file is created with mode `0600`; never share that file.
Configure each device's pinned public key and private-network endpoint in a
private JSON file shaped like
[`hand_mcp_stdio.example.json`](hand_mcp_stdio.example.json). For every device,
the authorization entry's `agent_id` and `folder` must match a grant made on
that Hand server.

Start the MCP process and call `hand_devices` once. This sends the public hello
and registers the MCP client as `PENDING` on each reachable unpaired device.
Verify the pending device ID and public key, then approve that exact ID on each
Hand device:

```sh
python3 hand_server.py pair-list
python3 hand_server.py pair-approve <mcp-client-device-id>
```

## MCP host registration

Register the stdio command in any compatible MCP host's server configuration.
For Codex, add this to its `config.toml`, replacing the checkout path:

```toml
[mcp_servers.wherevercode_hand]
command = "/path/to/wherevercode-agent/.venv/bin/python"
args = [
  "/path/to/wherevercode-agent/server/hand_mcp_stdio.py",
  "--config",
  "/Users/your-user/.config/wherevercode-agent/hand-mcp.json",
]
```

This host-specific stanza is only process startup wiring. Tool schemas,
identity, authorization selection, routing, encryption, and failure handling
remain independent of the Agent vendor.

## Safety and controls

An Agent host may provide `task_id`, `epoch`, and `goal_version` in a
task-scoped adapter config when launching one Agent run. These values correlate
App, MCP, and Hand audit records and initialize the signed control snapshot;
they do not grant authority. The tool arguments cannot override them. Pairing,
the configured device/grant, the signed v2 credential, expiry, and Hand-side
replay and stop fences are still checked independently on each call.

- Every operation chooses from the configured device registry by `device_id`
  and a configured grant by `authorization_id`; no request can supply a route
  or path root.
- The adapter pins both the responding Hand device ID and public key.
- `device_run` accepts only four control operations and carries the current
  goal/epoch snapshot. Stop blocks subsequent writes until `change_goal`.
- A replayed task credential is rejected before the second side effect.
- A failed device call never executes on the MCP host.

## Verification

Run the protocol and adapter unit tests:

```sh
.venv/bin/python -m unittest server.test_hand_server server.test_hand_mcp_stdio -v
```

Unit tests and an APK build do not prove a physical device acceptance. Check
the connected model explicitly; do not substitute another phone for the
requested device.
