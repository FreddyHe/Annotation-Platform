# AGENTS.md instructions for /root/autodl-fs/Annotation-Platform

# Global working rules

## API keys

This machine stores user API credentials in:

- `~/.config/ai-keys/env`

Before running commands that need Kaggle, Roboflow, or Hugging Face credentials, load them with:

```bash
source ~/.config/ai-keys/env
```

Available variables may include:

- `KAGGLE_API_TOKEN`
- `KAGGLE_USERNAME`
- `KAGGLE_KEY`
- `ROBOFLOW_API_KEY`
- `HF_TOKEN`
- `HF_HOME`
- `HF_HUB_CACHE`
- `HF_DATASETS_CACHE`

Never print full API keys or tokens. Only check whether they are loaded:

```bash
echo "KAGGLE_API_TOKEN loaded: ${KAGGLE_API_TOKEN:+yes}"
echo "ROBOFLOW_API_KEY loaded: ${ROBOFLOW_API_KEY:+yes}"
echo "HF_TOKEN loaded: ${HF_TOKEN:+yes}"
```

## Network and proxy rules

This server has a resident Mihomo proxy service running locally.

When any network operation fails or is slow, including but not limited to:

- `git clone`
- `git fetch`
- `git pull`
- `git submodule update`
- `hf download`
- Hugging Face dataset/model download
- Kaggle download
- Roboflow download
- `pip install`
- `npm install`
- `wget`
- `curl`

Do not give up immediately. First try to resolve the network issue by enabling the local Mihomo proxy.

Proxy environment file:

- `~/.config/network/mihomo_proxy.env`

Before retrying a failed network command, run:

```bash
source ~/.config/network/mihomo_proxy.env
```

Use HTTP proxy variables for Hugging Face CLI:

```bash
export HTTP_PROXY="http://127.0.0.1:7890"
export HTTPS_PROXY="http://127.0.0.1:7890"
unset ALL_PROXY
unset all_proxy
```

Do not use `socks5h://` in `ALL_PROXY` for Hugging Face CLI. `ALL_PROXY` should usually be empty.

Safe proxy checks:

```bash
echo "HTTPS_PROXY loaded: ${HTTPS_PROXY:+yes}"
echo "ALL_PROXY loaded: ${ALL_PROXY:+yes}"
ss -lntp | grep -E '7890|9090|mihomo|clash' || true
curl -I -x http://127.0.0.1:7890 https://huggingface.co
```

## Hugging Face

Before Hugging Face operations, load both credentials and proxy:

```bash
source ~/.config/ai-keys/env
source ~/.config/network/mihomo_proxy.env
hf auth whoami
```

Download a dataset:

```bash
hf download <org>/<dataset> --repo-type dataset --local-dir /root/autodl-fs/datasets/<dataset>
```

Download a model:

```bash
hf download <org>/<model> --local-dir /root/autodl-fs/models/<model>
```

## Kaggle

Before Kaggle operations:

```bash
source ~/.config/ai-keys/env
```

Some Kaggle CLI versions need legacy auth:

- `~/.kaggle/kaggle.json`
- or `KAGGLE_USERNAME` + `KAGGLE_KEY`

If `kaggle competitions list` says `Could not find kaggle.json`, do not assume `KAGGLE_API_TOKEN` is wrong.

## Roboflow

Before Roboflow operations:

```bash
source ~/.config/ai-keys/env
```

Python code should read the key from the environment:

```python
import os
from roboflow import Roboflow

rf = Roboflow(api_key=os.environ["ROBOFLOW_API_KEY"])
```

## Style

Be practical and command-oriented. Give exact shell commands. Explain errors in simple Chinese. Do not expose secrets.

# Project engineering rules

## Project profile

This repository is an engineering product project, not a research experiment repository.

Main modules:

- `backend-springboot/`: Spring Boot backend, API prefix `/api/v1`.
- `frontend-vue/`: Vue 3 + Vite + Element Plus frontend.
- `algorithm-service/`: Python/FastAPI algorithm service.
- `scripts/`: runtime, training, and service helper scripts.
- `doc/`: active development and remediation documents.

Current active development document:

- `/root/autodl-fs/Annotation-Platform/doc/develop0521.md`

The default task in this repository is to implement the active engineering development document stage by stage, run validation, record progress, and continue until every acceptance item is completed or a real blocker remains after serious debugging.

## Required workflow

At the start of every engineering run:

1. Read the active development document.
2. Read the persistent progress file if it exists.
3. Check `git status --short` and do not overwrite unrelated user changes.
4. Identify completed stages, unfinished stages, validation criteria, and blockers.
5. Implement the next unfinished stage.
6. Run relevant backend, frontend, service, and smoke validations.
7. If a command fails, debug and try reasonable fixes independently.
8. Update the progress file after meaningful changes.
9. Continue to the next stage until all acceptance criteria in the document pass, or until blocked by missing credentials, missing data, hardware failure, storage, endpoint access, or an unresolved error after multiple serious attempts.

Do not stop after a single stage when the active document contains multiple stages and the user has asked for end-to-end completion.

## Progress tracking

Maintain a persistent progress file for the active document:

- `/root/autodl-fs/Annotation-Platform/doc/develop0521.progress.md`

After each meaningful step, update it with:

- completed stages
- current stage
- files changed
- commands run
- validation result
- failed attempts and fixes
- remaining stages
- next action

Before doing new work, always read this progress file first if it exists.

## Validation standard

A stage is complete only when its acceptance criteria in the active development document pass.

For every completed stage, record:

- evidence path or command output summary
- output artifact path if any
- validation command
- pass/fail status
- remaining risk if any

Health checks, compilation, and manual smoke checks are useful engineering validation, but do not claim an item is fully accepted when the exact acceptance condition has not been tested.

## Engineering priorities for this project

For the project-management-page remediation work, prioritize in this order:

1. Project/organization authorization for every `projectId`, `jobId`, `trainingRecordId`, export, upload, and Label Studio entrypoint.
2. Secret handling: never expose full tokens or passwords in API responses, logs, frontend UI, or committed config.
3. Upload and file-path safety: sanitize filenames, normalize paths, reject path traversal, prevent Zip Slip, and check ownership before file operations.
4. API contract stability: pagination, labels, status enums, error responses, and frontend compatibility.
5. UI correctness: hide unimplemented entries, remove debug logs, provide clear user-facing errors.
6. Production hardening: export downloads, cleanup tasks, physical file cleanup, statistics consistency, and regression tests.

## Common commands

Backend compile/test:

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn test
```

Frontend build:

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
source /root/.nvm/nvm.sh
nvm use 18
npm run build
```

Frontend dev service:

```bash
/root/autodl-fs/Annotation-Platform/scripts/start_frontend_service.sh
```

Backend service:

```bash
/root/autodl-fs/Annotation-Platform/scripts/start_backend_service.sh
```

Container startup hook:

```bash
/etc/autodl-annotation-platform-frontend.sh
```

Smoke checks:

```bash
curl -sS http://127.0.0.1:8080/api/v1/actuator/health
curl -sS -I http://127.0.0.1:6006/
curl -sS http://127.0.0.1:6006/api/v1/actuator/health
```

## GPU/NVML handling

When working in AutoDL or any remote GPU container, treat the following as infrastructure failure, not algorithm failure:

- `nvidia-smi` reports `Failed to initialize NVML: Unknown Error`
- `nvidia-smi -L` also fails with an NVML initialization error
- `/dev/nvidia*` still exists, but NVML cannot initialize
- GPU access disappears after the container has been running for a while

Required behavior:

1. Do not continue long-running training, evaluation, or agent workflows while GPU/NVML is unavailable.
2. Do not report results from this state as valid experiment or engineering validation results.
3. Save diagnostics, logs, and any available checkpoint.
4. Run at least:

```bash
date
hostname
uptime
nvidia-smi || true
nvidia-smi -L || true
ls -l /dev/nvidia* 2>/dev/null || true
ls -l /dev/char/* 2>/dev/null | grep -E '195|507|nvidia' | head -100 || true
cat /proc/driver/nvidia/version || true
dmesg -T 2>/dev/null | egrep -i 'NVRM|Xid|nvidia|GPU has fallen|fallen off|nvml' | tail -100 || true
```

5. Save the diagnostic output to a timestamped file, for example:

```bash
/root/gpu_diag_broken_$(date +%Y%m%d_%H%M%S).log
```

6. Stop training or experiment processes safely after checkpoints and logs are saved:

```bash
pkill -TERM -f "python|torchrun|accelerate" || true
```

7. Notify the user explicitly that GPU/NVML dropped and that the AutoDL container likely needs restart or recreation.
8. Mark the current stage as `INFRA_FAILURE_GPU_NVML_LOST`.
9. After restart, verify `nvidia-smi` and `nvidia-smi -L` before resuming.
10. Resume only from the latest valid checkpoint unless the user explicitly asks to restart from scratch.

## Final response requirement

When reporting back, summarize:

1. stages completed in this run
2. stages still remaining
3. validation results
4. important artifact paths
5. exact next command or next stage

Keep the response concise, but do not hide blockers or failed validations.
