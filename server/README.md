# KUN gateway — small self-hosted pilot

This server runs the same model for all authenticated users through a private Ollama instance. CPU works for a small smoke test; useful shared capacity generally requires choosing sufficient GPU/RAM and a suitable model. Hosting, electricity and hardware are your costs. Nothing here is deployed automatically.

## HTTPS setup on your own Linux server

Prerequisites: Docker Compose, Python 3, a domain whose DNS points to this server, inbound ports 80/443, enough storage/RAM for your model. Commands run from this `server/` directory. No paid resource is provisioned by these files.

```bash
cp .env.example .env
```

Edit `.env`: set `KUN_DOMAIN` to your real domain (hostname only). `KUN_MODEL=gemma3:1b` is a modest smoke-test default, not a promise of strong Urdu quality. Choose and evaluate a larger suitable model when compute permits; check its license.

Provision the first user; the command prints that user's secret once:

```bash
python3 provision.py add arsalan --file config/users.json
sudo chown 65532:65532 config/users.json
sudo chmod 600 config/users.json
# Keep the directory traversable by the container user, without listing permission for others.
sudo chmod 711 config

docker compose up -d --build
docker compose exec ollama ollama pull gemma3:1b
```

`users.json` contains token hashes, not the usable tokens. Keep the printed token private. Enter `https://YOUR_DOMAIN` and that token in Android Server settings. Do not embed one owner token into an APK shared with everybody.

The gateway can start before model download finishes; inference returns 502 until the chosen model is ready. `/healthz` only proves the gateway is alive, not model readiness. Test a real chat before giving access to users.

## Add, rotate or revoke individual user access

Use unique user IDs. Adding the same ID replaces its old token immediately.

```bash
# sudo is needed after assigning the config file to the container UID.
sudo python3 provision.py add user002 --file config/users.json
sudo chown 65532:65532 config/users.json
sudo chmod 600 config/users.json

sudo python3 provision.py revoke user002 --file config/users.json
sudo chown 65532:65532 config/users.json
sudo chmod 600 config/users.json
```

The whole config directory is mounted so atomic replacements are visible; users are read on each request. Changing file ownership after replacement can cause a brief authentication outage during this manual administration. Existing in-flight inference is allowed to finish after revocation. Run one provisioning administrator at a time. Revoking every user makes authentication fail closed; provision one user before restarting the gateway.

## Request shape

`POST /v1/chat/completions`, `Authorization: Bearer USER_TOKEN`, `Content-Type: application/json`:

```json
{"messages":[{"role":"user","content":"اردو میں جواب دیں"}]}
```

The gateway chooses the model, system message and 512-output-token cap; users cannot override them. Response uses `choices[0].message.content`. No prompt text is stored by this gateway. Requests still reach the machine hosting inference.

## Limits and deployment scope

- 10 accepted requests/minute/user, one active request/user and two active upstream jobs overall by default. These are fair-use capacity controls, not a monthly token quota.
- At most nine alternating messages, 5,000 characters in the final question, 12,000 total characters, 64 KiB request body, 256 KiB upstream response. Model token limits still apply.
- A character bound does not guarantee a model's context can hold the text. Tune limits/model context after multilingual testing.
- No retries or model fallback; 401 invalid credentials, 429 user busy/rate limit, 503 shared capacity busy, 502 upstream failure.
- Limits live in one process and reset on restart. Do not run multiple replicas without shared rate/concurrency storage.
- Python's basic HTTP server is behind Caddy for this limited pilot. Before broad public distribution, use a hardened application server, edge abuse protection, shared quota state, real account authentication/device token lifecycle, monitoring and load testing.
- Ollama and gateway have NO host-published ports. Only Caddy exposes HTTPS. Never expose unauthenticated Ollama to the internet.
- Container image tags are mutable examples; pin reviewed digests before a production rollout.
- This Compose defaults to CPU; GPU provisioning/drivers/pass-through are not included. Choose hardware first, then configure the appropriate Ollama GPU container setup.

Optional advanced upstream: `KUN_UPSTREAM` is the OpenAI-compatible base URL ending in `/v1`; `KUN_UPSTREAM_KEY` is a backend-only provider credential if required. It is intentionally not supplied in the public configuration. Switching away from self-hosted Ollama may add provider fees, quotas and different data handling. No automatic FreeLLMAPI fallback is enabled.

## Tests

```bash
python3 -m unittest -v
```

Tests use local mock inference and temporary fake credentials only. They do not download a model or contact a provider.
