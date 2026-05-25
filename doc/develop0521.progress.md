# develop0521 progress

Last updated: 2026-05-21

## Current status

- Active document: `/root/autodl-fs/Annotation-Platform/doc/develop0521.md`
- Overall status: implemented and smoke-validated
- Backend service: `0.0.0.0:8080`, health `UP`
- Frontend service: `0.0.0.0:6006`
- Public frontend URL through AutoDL proxy: `http://122.51.47.91:21172/`

## Stage 1 / P0

### P0-01 project-level authorization

Status: completed.

Implemented:

- Added `ProjectAccessService`.
- Added organization-scoped project lookup in `ProjectRepository`.
- Added job ownership lookup in `AutoAnnotationJobRepository`.
- Added organization checks to project detail, update, delete, images, stats, review, export, training, LS health, flywheel, round, config, upload, auto annotation, training, algorithm task, edge simulator, and data-point endpoints.
- Scoped global task lists and completed training lists to the current organization.

Evidence:

- `mvn test` passed.
- Admin smoke: `/projects?page=0&size=2` returns `{content, pageable}` for current org.
- Admin smoke: `/auto-annotation/results/project-769` returns HTTP `501` after project access check, not fake success.

Remaining risk:

- A true two-organization negative test was not run because only the provided admin credential was used during smoke. The code path is enforced by repository-level `findByIdAndOrganizationId`.

### P0-02 plaintext credential exposure

Status: completed.

Implemented:

- Removed `lsPassword` from `UserProfileResponse`.
- Stopped returning LS plaintext password in `UserServiceImpl`.
- Removed frontend LS password display and password auto-post flow.
- Stopped saving login password into `lsPlainPassword` during normal login.
- Changed JWT secret and LS admin token config to environment placeholders.
- Removed debug logs that printed generated Label Studio XML/project payloads.

Evidence:

- Smoke: `/users/me/profile` response does not contain `lsPassword`.
- Grep check found no `lsPassword`, old hardcoded JWT secret, hardcoded admin-token, `[DEBUG]`, or `console.log` in changed source paths.

Remaining risk:

- Historical database rows may still contain prior `ls_plain_password` values. A data migration or manual cleanup is recommended for already-stored values.

### P0-03 upload filename and path safety

Status: completed.

Implemented:

- Added `sanitizeFileId`, `sanitizeFilename`, `resolveInside`, and upload-root path normalization in `FileUploadServiceImpl`.
- Normalized chunk upload, merge, manifest, zip extraction, and delete paths.
- ZIP extraction now writes only safe basenames, avoids overwrite, and marks extracted images `COMPLETED`.
- Upload chunk, merge, progress, chunks, and delete endpoints now check project ownership.
- `/upload/view` now rejects traversal outside upload root.

Evidence:

- `mvn test` passed.
- Smoke: `DELETE /upload/file?filePath=../tmp/evil.jpg` returns `success=false`, message `文件路径非法`.

## Stage 2 / P1

### P1-01 pagination contract

Status: completed.

Implemented:

- Backend project list returns `data.content` plus `data.pageable`.
- Frontend project list and dashboard support the new contract and retain compatibility with old array responses.

Evidence:

- Smoke: project list contract verified with admin; `content` is array and `pageable` exists.

### P1-02 labels model validation and XML escape

Status: completed.

Implemented:

- Added backend label validation for create/update DTOs.
- Added backend trim/dedupe normalization before project save.
- Added frontend trim/dedupe in project creation and label definition save.
- Escaped Label Studio XML label attributes.

Evidence:

- `mvn test` and frontend build passed.

### P1-03 frontend status mapping

Status: completed.

Implemented:

- Added `frontend-vue/src/utils/projectStatus.js`.
- Project list, dashboard, and project detail now share the canonical project statuses:
  `DRAFT`, `UPLOADING`, `DETECTING`, `CLEANING`, `SYNCING`, `COMPLETED`, `FAILED`.

### P1-04 video extract tab

Status: completed.

Implemented:

- Hidden the video extraction tab behind a disabled feature flag because `/upload/extract-frames` is not implemented.

### P1-05 auto annotation concurrency and cancellation

Status: completed.

Implemented:

- Prevented concurrent auto annotation jobs per project for `PENDING`, `RUNNING`, and `CANCELLING`.
- Cancellation now marks the job `CANCELLED` and restores project state to `DRAFT`, not `FAILED`.
- Auto-annotation results endpoint returns HTTP `501` instead of fake success.

Evidence:

- Smoke: `/auto-annotation/results/project-769` returns HTTP `501`.

### P1-06 project name uniqueness

Status: completed.

Implemented:

- Create and update both check duplicate project names inside current organization.
- Added JPA unique constraint for `(organization_id, name)`.

## Stage 3 / P2

### P2-01 image status/statistics consistency

Status: completed.

Implemented:

- Upload merge refreshes project `totalImages` from database count.
- ZIP-extracted images are saved as `COMPLETED`.
- Project stats uses image counts from the database.

### P2-02 export as file download

Status: completed.

Implemented:

- Export now writes a file under `uploads/exports/{projectId}`.
- Added protected download endpoint `/projects/{id}/exports/{filename}`.
- Frontend supports both old data URL and new download URL.

### P2-03 project deletion physical cleanup

Status: completed.

Implemented:

- Project deletion now attempts recursive cleanup of the project upload directory after DB/LS cleanup.
- Cleanup failures are logged as warnings and do not corrupt DB deletion.

### P2-04 frontend UX cleanup

Status: completed.

Implemented:

- Removed visible unimplemented edit action.
- Removed debug console log.
- Added singleton 401 prompt.
- Improved date formatting in project/dashboard lists.
- Removed LS password display from project detail and profile LS jump logic.

## Commands run

```bash
sed -n '1,260p' AGENTS.md
sed -n '1,1160p' doc/develop0521.md
git status --short
mvn test -DskipTests
mvn test
source /root/.nvm/nvm.sh && nvm use 18 && npm ci
source /root/.nvm/nvm.sh && nvm use 18 && npm run build
node -e "const p=require('./package.json'); console.log(p.scripts && p.scripts.test ? p.scripts.test : 'NO_TEST_SCRIPT')"
mvn package -DskipTests
/root/autodl-fs/Annotation-Platform/scripts/start_backend_service.sh
/root/autodl-fs/Annotation-Platform/scripts/start_frontend_service.sh
curl -sS http://127.0.0.1:8080/api/v1/actuator/health
curl -sSI http://127.0.0.1:6006/
```

## Validation result

- Backend `mvn test`: passed, no test sources present.
- Frontend `npm ci`: passed.
- Frontend `npm run build`: passed.
- Frontend `npm run test`: not run because `package.json` has no `test` script.
- Backend health: `UP`.
- Frontend local HTTP: `200 OK`.
- Frontend public proxy HTTP: `200 OK` at `http://122.51.47.91:21172/`.
- Admin smoke login: passed.
- Profile plaintext check: passed, `lsPassword` absent.
- Project pagination contract: passed.
- Auto annotation results endpoint: HTTP `501`, as required for unimplemented results.
- Upload path traversal delete smoke: rejected with `文件路径非法`.

## Changed files summary

- `AGENTS.md`: rewritten for engineering execution, removed research/HTML Artifact requirements.
- `backend-springboot/src/main/java/com/annotation/platform/service/ProjectAccessService.java`: new authorization helper.
- Backend controllers/repositories/services: authorization, credentials, uploads, labels, export, delete cleanup, auto annotation hardening.
- `backend-springboot/src/main/resources/application.yml`: environment placeholders for sensitive values.
- Frontend project management views/components: pagination, status mapping, hidden video tab, LS credential removal, export download, UX cleanup.
- `scripts/start_backend_service.sh`, `scripts/start_frontend_service.sh`: changed service startup to `setsid` for durable detached launch.

## Remaining follow-up

- Clean historical `users.ls_plain_password` values from the existing database.
- Add real automated two-organization authorization tests when test fixtures are available.
- Add frontend test script/CI target if this project expects UI unit tests.
