import os, json, sqlite3, time, threading
from datetime import datetime, timezone
from typing import Optional
from fastapi import FastAPI, Header, HTTPException, Depends
from pydantic import BaseModel, Field
import requests

DB_PATH = os.getenv("SOCIALDESK_DB_PATH", "socialdesk.db")
API_KEY = os.getenv("SOCIALDESK_API_KEY", "")
GRAPH_VERSION = os.getenv("META_GRAPH_VERSION", "v26.0")
app = FastAPI(title="SocialDesk API", version="2.0.0", description="Authorized Facebook Page publishing and scheduling API")
stop_event = threading.Event()

def pages_config():
    raw = os.getenv("SOCIALDESK_PAGES_JSON", "{}")
    try:
        data = json.loads(raw)
        if not isinstance(data, dict): raise ValueError("expected object")
        return data
    except Exception:
        return {}

def init_db():
    with sqlite3.connect(DB_PATH) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS jobs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            page_id TEXT NOT NULL,
            message TEXT NOT NULL,
            link TEXT NOT NULL DEFAULT '',
            scheduled_at TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'scheduled',
            post_id TEXT NOT NULL DEFAULT '',
            error TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL
        )""")
        db.commit()

def require_key(x_api_key: Optional[str] = Header(default=None)):
    if not API_KEY:
        raise HTTPException(503, "Backend is not configured: set SOCIALDESK_API_KEY.")
    if not x_api_key or x_api_key != API_KEY:
        raise HTTPException(401, "Invalid or missing SocialDesk API key.")

def parse_dt(value: str) -> datetime:
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            raise ValueError("timezone is required")
        return dt.astimezone(timezone.utc)
    except Exception as exc:
        raise HTTPException(422, "scheduled_at must be ISO 8601 and include a timezone, e.g. 2026-11-01T10:30:00+03:00") from exc

def publish_to_meta(page_id: str, message: str, link: str = ""):
    page = pages_config().get(page_id)
    if not page or not page.get("access_token"):
        raise RuntimeError(f"Page {page_id} is not configured on the server.")
    if not message.strip() and not link.strip():
        raise RuntimeError("Post text or link is required.")
    url = f"https://graph.facebook.com/{GRAPH_VERSION}/{page_id}/feed"
    payload = {"access_token": page["access_token"]}
    if message.strip(): payload["message"] = message.strip()
    if link.strip(): payload["link"] = link.strip()
    try:
        response = requests.post(url, data=payload, timeout=25)
        data = response.json()
    except Exception as exc:
        raise RuntimeError(f"Could not reach Meta Graph API: {exc}") from exc
    if response.status_code >= 400 or "error" in data:
        err = data.get("error", {})
        raise RuntimeError(err.get("message", f"Meta returned HTTP {response.status_code}"))
    return data.get("id") or data.get("post_id") or ""

def worker():
    while not stop_event.is_set():
        try:
            now = datetime.now(timezone.utc)
            with sqlite3.connect(DB_PATH) as db:
                db.row_factory = sqlite3.Row
                rows = db.execute("SELECT * FROM jobs WHERE status='scheduled' ORDER BY id LIMIT 10").fetchall()
            for row in rows:
                if stop_event.is_set(): break
                try:
                    due = parse_dt(row["scheduled_at"])
                    if due > now: continue
                    with sqlite3.connect(DB_PATH) as db:
                        cur = db.execute("UPDATE jobs SET status='processing' WHERE id=? AND status='scheduled'", (row["id"],))
                        if cur.rowcount == 0: continue
                        db.commit()
                    try:
                        post_id = publish_to_meta(row["page_id"], row["message"], row["link"])
                        with sqlite3.connect(DB_PATH) as db:
                            db.execute("UPDATE jobs SET status='published', post_id=?, error='' WHERE id=?", (post_id, row["id"]))
                            db.commit()
                    except Exception as exc:
                        with sqlite3.connect(DB_PATH) as db:
                            db.execute("UPDATE jobs SET status='failed', error=? WHERE id=?", (str(exc)[:1000], row["id"]))
                            db.commit()
                except Exception as exc:
                    with sqlite3.connect(DB_PATH) as db:
                        db.execute("UPDATE jobs SET status='failed', error=? WHERE id=?", (str(exc)[:1000], row["id"]))
                        db.commit()
        except Exception:
            pass
        stop_event.wait(5)

@app.on_event("startup")
def startup():
    init_db()
    t = threading.Thread(target=worker, daemon=True)
    t.start()

@app.on_event("shutdown")
def shutdown():
    stop_event.set()

@app.get("/health")
def health():
    return {"status": "ok", "service": "SocialDesk API", "configured_pages": len(pages_config())}

@app.get("/api/pages", dependencies=[Depends(require_key)])
def list_pages():
    cfg = pages_config()
    return {"pages": [{"id": str(pid), "name": str(p.get("name", pid))} for pid, p in cfg.items() if p.get("access_token")]}

class PublishRequest(BaseModel):
    page_id: str
    message: str = ""
    link: str = ""

@app.post("/api/publish", dependencies=[Depends(require_key)])
def publish(req: PublishRequest):
    if req.page_id not in pages_config():
        raise HTTPException(404, "Page is not configured on the backend.")
    try:
        post_id = publish_to_meta(req.page_id, req.message, req.link)
        return {"ok": True, "post_id": post_id, "status": "published"}
    except Exception as exc:
        raise HTTPException(502, str(exc))

class ScheduleRequest(BaseModel):
    page_id: str
    message: str = ""
    link: str = ""
    scheduled_at: str

@app.get("/api/jobs", dependencies=[Depends(require_key)])
def list_jobs():
    with sqlite3.connect(DB_PATH) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute("""SELECT jobs.*, COALESCE(json_extract('null','$'),'') AS unused
                             FROM jobs ORDER BY id DESC LIMIT 200""").fetchall()
    cfg = pages_config()
    return {"jobs": [{
        "id": r["id"], "page_id": r["page_id"],
        "page_name": cfg.get(r["page_id"], {}).get("name", r["page_id"]),
        "message": r["message"], "link": r["link"], "scheduled_at": r["scheduled_at"],
        "status": r["status"], "post_id": r["post_id"], "error": r["error"]
    } for r in rows]}

@app.post("/api/jobs", dependencies=[Depends(require_key)])
def create_job(req: ScheduleRequest):
    cfg = pages_config()
    if req.page_id not in cfg or not cfg[req.page_id].get("access_token"):
        raise HTTPException(404, "Page is not configured on the backend.")
    if not req.message.strip() and not req.link.strip():
        raise HTTPException(422, "Post text or link is required.")
    due = parse_dt(req.scheduled_at)
    if due.timestamp() <= time.time():
        raise HTTPException(422, "Schedule time must be in the future.")
    canonical = due.isoformat()
    with sqlite3.connect(DB_PATH) as db:
        cur = db.execute("INSERT INTO jobs(page_id,message,link,scheduled_at,status,created_at) VALUES(?,?,?,?,?,?)",
                         (req.page_id, req.message.strip(), req.link.strip(), canonical, "scheduled", datetime.now(timezone.utc).isoformat()))
        db.commit()
        jid = cur.lastrowid
    return {"ok": True, "id": jid, "status": "scheduled", "scheduled_at": canonical}

@app.post("/api/jobs/{job_id}/cancel", dependencies=[Depends(require_key)])
def cancel_job(job_id: int):
    with sqlite3.connect(DB_PATH) as db:
        cur = db.execute("UPDATE jobs SET status='cancelled' WHERE id=? AND status='scheduled'", (job_id,))
        db.commit()
    if cur.rowcount == 0:
        raise HTTPException(409, "Job not found or no longer cancellable.")
    return {"ok": True, "id": job_id, "status": "cancelled"}

@app.post("/api/jobs/{job_id}/retry", dependencies=[Depends(require_key)])
def retry_job(job_id: int):
    with sqlite3.connect(DB_PATH) as db:
        cur = db.execute("UPDATE jobs SET status='scheduled', error='', post_id='' WHERE id=? AND status='failed'", (job_id,))
        db.commit()
    if cur.rowcount == 0:
        raise HTTPException(409, "Only failed jobs can be retried.")
    return {"ok": True, "id": job_id, "status": "scheduled"}
