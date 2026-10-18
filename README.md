# SocialDesk Android + API — Phase 2

This repository is a substantial next step from the local organizer:
- Android app with backend connection settings
- Lists server-configured Facebook Pages
- Publishes text/link posts through Meta's official Graph API via the backend
- Schedules posts and shows the job queue/history
- Cancels scheduled jobs and retries failed jobs (backend endpoints included; UI actions can be expanded next)
- Backend keeps Page access tokens server-side
- GitHub Actions builds the Android debug APK

**Not a full MKT Care clone yet.** This phase covers the first real integration: authorized Facebook Page text/link publishing and scheduling. It does not automate reactions, fake engagement, or evade platform safeguards. Photos/video, comments/inbox, insights, multi-page bulk campaigns, media library, OAuth onboarding, and role-based users are future phases.

## Part A: Build the APK with GitHub
1. Create a GitHub repository.
2. Upload the contents of this folder to the repository root, including `.github/workflows/build-apk.yml`.
3. Commit to `main`.
4. Open Actions → **Build SocialDesk Android APK**.
5. Download the `SocialDesk-phase2-debug-apk` artifact from the successful run.
6. Extract `app-debug.apk`, copy it to your phone, and install it.

## Part B: Set up the backend
The Android app requires a public HTTPS backend. See `backend/README.md`. The backend uses environment variables:
- `SOCIALDESK_API_KEY`
- `SOCIALDESK_PAGES_JSON`
- `META_GRAPH_VERSION`
- optional `SOCIALDESK_DB_PATH`

Do not put Page access tokens in the APK or GitHub repository. Add them only to the backend host's private environment variables. Keep the backend online continuously for scheduled jobs.

## Part C: Connect Android to backend
In the app, open Setup and enter:
- Backend URL, e.g. `https://your-backend-host.example`
- the same `SOCIALDESK_API_KEY` value configured on the server

Tap Save settings, then Home → Connect / refresh. Once the server and Meta permissions are configured, the app should show your configured Pages.

## Meta prerequisites
Create a Meta developer app and obtain a Page access token for a Page you administer. Configure the required permissions in Meta. `pages_manage_posts` is used to publish Page content; `pages_show_list` is used to list managed Pages. Production access for other users may require Meta app review and business verification. API access is controlled by Meta, so this package cannot bypass those requirements.

## Test carefully
First test with a Page you administer and a simple non-sensitive post. Confirm the returned post ID and check the actual Page. Do not assume a job was published unless the backend status confirms it and the post appears on Facebook.
