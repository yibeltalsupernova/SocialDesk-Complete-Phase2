# SocialDesk API backend

This backend provides authenticated endpoints for listing configured Facebook Pages, publishing text/link posts, and scheduling posts. It stores Page tokens in server environment variables, never in the Android app.

## Important requirements
- Use only Pages you administer or are authorized to manage.
- The Meta app needs the appropriate permissions, commonly `pages_show_list`, `pages_read_engagement`, and `pages_manage_posts`.
- Meta may require App Review / Business Verification for production use or other users.
- Never commit tokens or the API key to GitHub.
- Use HTTPS in production.
- A free host that sleeps or lacks persistent storage is not reliable for scheduled publishing. Use a service that stays awake and retains its database.

## Local run
Python 3.11+:
```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
```
Set environment variables before starting. Example PowerShell:
```powershell
$env:SOCIALDESK_API_KEY="replace-with-a-long-random-secret"
$env:META_GRAPH_VERSION="v26.0"
$env:SOCIALDESK_PAGES_JSON='{"YOUR_PAGE_ID":{"name":"Your Page Name","access_token":"YOUR_PAGE_ACCESS_TOKEN"}}'
uvicorn main:app --host 0.0.0.0 --port 8000
```
Test `http://127.0.0.1:8000/health`. Local HTTP is for local development only. The Android app intentionally requires HTTPS, so for phone testing use a deployed HTTPS backend.

## Endpoints
- `GET /health` — public health check
- `GET /api/pages` — configured Pages
- `POST /api/publish` — publish a text/link post immediately
- `GET /api/jobs` — queue and history
- `POST /api/jobs` — schedule a future post (ISO 8601 timestamp with timezone)
- `POST /api/jobs/{id}/cancel` — cancel scheduled job
- `POST /api/jobs/{id}/retry` — retry failed job

Protected endpoints require `X-API-Key` header.

## Scheduling behavior
A background worker checks due jobs every five seconds. Keep the service running continuously. Failed posts are marked `failed` and include a readable error. Review failures before retrying.
