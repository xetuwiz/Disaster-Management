# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ClimaSphere is a Sri Lanka disaster management and weather intelligence platform. Monorepo layout with four independently-run services:

| Service | Stack | Port |
|---------|-------|------|
| **Backend** | Spring Boot 3.2, Java 21, Maven | 8080 |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS | 5173 |
| **AI Service** | Python FastAPI (SARIMAX/XGBoost/LSTM/Holt-Winters ensemble) | 8000 |
| **Geo** | Sri Lanka administrative boundary GeoJSON files (ADM0–ADM5) | — |

Infrastructure: PostgreSQL 16 + PostGIS 3.4, Redis 7.

## Essential Commands

### Backend
```bash
cd backend
./mvnw spring-boot:run          # Start dev server
./mvnw compile                   # Compile
./mvnw package                   # Build JAR
./mvnw flyway:migrate            # Run DB migrations
./mvnw test                      # Run all tests (no test classes exist yet)
./mvnw test -Dtest=ClassName     # Run a single test class
```

### Frontend
```bash
cd frontend
npm run dev                      # Vite dev server
npm run build                    # tsc -b && vite build
npm run lint                     # ESLint
npm run preview                  # Preview production build
```

### Infrastructure
```bash
docker compose up -d             # Start PostgreSQL + Redis
```

### AI Service
```bash
cd ai-service
uvicorn main:app --reload        # FastAPI dev server
```

## Backend Architecture (`com.climasphere.backend`)

### Package Structure

```
controller/   — 19 REST controllers (Admin, Setup, Auth, Weather, Map, Disasters,
                Reports, Flood, Meteo, Analytics, Emergency, Notifications,
                AlertRules, Content/Guides/FAQ, Profile, SpatialUnitAdmin,
                WeatherNodeAdmin, SystemAdmin, AiExport)
dto/          — Request/response objects grouped by domain
                (admin, analytics, auth, content, disaster, emergency, flood,
                 map, meteo, report, user, weather)
entity/enums/ — 42 entity classes, 9 Java enum classes
repository/   — 34 Spring Data JPA repos (most with UUID PKs)
service/      — 23 service classes
scheduler/    — 6 schedulers (weather sync, flood sync, meteo sync,
                cache warming, historical backfill, alert evaluation)
security/     — JwtAuthenticationFilter, CustomUserDetailsService, SecurityConfig
client/       — 5 external API clients (OpenMeteoClient, OpenWeatherMapClient,
                ArcGISClient, RivernetClient, MeteoSLClient)
exception/    — GlobalExceptionHandler + 4 custom RuntimeExceptions
config/       — ApiKeyConfig, RedisConfig, RestTemplateConfig
```

### Key Architectural Rules

1. **Weather is ALWAYS IDW-interpolated** from 668 pre-computed weather nodes. User requests NEVER call external APIs. External API calls happen ONLY in scheduled sync jobs.
2. **`disaster_warnings` has NO `spatial_unit_id`** — uses `warning_spatial_units` join table (multi-target, any level mix).
3. **Warning resolution walks UP** the parent chain via `getActiveWarningsForSpatialUnit()` to find ancestor warnings. A Country warning (`pcode=LK`) applies to every polygon on the map.
4. **All entities**: UUID PKs with `@GeneratedValue(strategy=GenerationType.AUTO)`, Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`. Exception: `WeatherNodeTelemetryLog` uses `Long` IDENTITY, `UserPreferences` and `WeatherNodeLiveCache` use entity FK as PK.
5. **JWT**: 15 min access token (Bearer header), 30 day refresh token (HttpOnly Secure SameSite=Strict cookie). BCrypt strength 12 (strength 10 for refresh tokens).
6. **External APIs**: Open-Meteo (weather forecast/historical), OpenWeatherMap (current weather for volatile nodes), ArcGIS (flood gauge data), Rivernet (river sensor data), Meteo SL (scraped bulletins).

### SecurityConfig Auth Tiers

| Tier | Endpoints |
|------|-----------|
| **Public** | POST /auth/register, /auth/login, /auth/refresh; GET /weather/**, /disasters/warnings/active, /reports/public, /flood/dashboard, /guides/**, /faq/**, /map/custom-zones, /actuator/health |
| **Admin** | All /api/v1/admin/** paths |
| **Role-based** | /api/v1/admin/emergency/**, /api/v1/admin/sos/**, /api/v1/admin/warnings/** (ADMIN, RESPONDER, or GOVT_OFFICIAL) |
| **Authenticated** | Everything else |

### @Scheduled Methods

| Scheduler | Method | Default Interval |
|-----------|--------|-----------------|
| WeatherSyncScheduler | syncWeatherNodes | 30 min |
| WeatherSyncScheduler | syncDailyForecasts | Daily 01:30 |
| WeatherSyncScheduler | evictWeatherCaches | 5s initial + 30 min |
| FloodSyncScheduler | syncFloodGauges | 15 min (hardcoded) |
| FloodSyncScheduler | syncRivernetDevices | 15 min (hardcoded) |
| MeteoSyncScheduler | syncMeteoContent | 15 min |
| CacheWarmingScheduler | warmCaches | 15 min |
| HistoricalBackfillScheduler | backfillHistory | Daily 02:00 |
| AlertRuleEvaluator | evaluateAlertRules | 15 min |
| AlertEngineService | processRawDisasterAlerts | 5 min |

### Database Migrations

Flyway migrations (no V10 — sequence is V1-V9, V11):
- V1: Enums (13 PostgreSQL enum types)
- V2: User/auth tables + seed 6 roles (guest, user, analyst, admin, volunteer, responder)
- V3: Spatial & weather tables (spatial_units, weather_nodes, live_cache, historical, forecast)
- V4: Disaster & flood tables (warnings, reports, SOS, flood gauges, Rivernet, volunteer tasks)
- V5: Analytics, alerts, content (forecast projections, alert rules, notifications, guides, FAQ, system configs, scraped data)
- V6: PostGIS extension + geom column on spatial_units
- V7: AQI columns (us_aqi, pm10, pm2_5)
- V8: Fix Rivernet alert_levels JSONB→ TEXT
- V9: Spatial forecast snapshots table
- V11: Defensive duplicate of V9 (IF NOT EXISTS guards)

Seed data file (`seed_demo_data.sql`) is referenced but does not exist on disk.

### Known Backend Issues

| Severity | Issue | Detail |
|----------|-------|--------|
| **HIGH** | `DisasterCategory.OTHER` missing from V1 migration | Java enum has `OTHER`, DB enum only has 10 values. Persists with constraint violation. |
| **HIGH** | `User` entity uses `FetchType.EAGER` for roles | N+1 performance issue on user listing endpoints |
| **MEDIUM** | Two alert systems with similar names | `AlertEngineService` (5 min, processes DMC raw warnings) vs `AlertRuleEvaluator` (15 min, processes user alert rules). Confusing naming. |
| **MEDIUM** | Tables without entity classes | district_severity_cache, mass_alerts, anomaly_scores, dmc_raw_warnings, api_usage_logs |
| **LOW** | Missing password reset / email verification flow | `changePassword` requires current password, no "forgot password" endpoint |
| **LOW** | No test classes exist | spring-boot-starter-test is a dependency but zero test files |

## Weather Engine (Added in weather-engine branch)

### New Data Sources
- **Yr.no (ECMWF backbone)** — primary model, 0-72h hourly per node, 1 req/sec rate limit
- **Meteo.gov.lk** — 24 physical stations, Excel download every 3h
- **NOAA METAR** — 2 airports (VCBI, VCRI), JSON every 30min
- **JAXA GSMaP** — satellite rainfall grid, FTP binary, hourly

### New Clients (`com.sidms.backend.client`)
`YrNoClient`, `MeteoGovLkClient`, `NoaaMetarClient`, `JaxaGsmapClient`

### New Schedulers (`com.sidms.backend.scheduler`)

| Scheduler | Job Name | Cooldown |
|-----------|----------|----------|
| `YrNoSyncScheduler` | `yrno_sync` | 6h |
| `MeteoStationSyncScheduler` | `meteo_gov_lk_sync` | 3h |
| `NoaaMetarSyncScheduler` | `noaa_metar_sync` | 30min |
| `JaxaGsmapSyncScheduler` | `jaxa_gsmap_sync` | 1h |
| `BiasRecalculationScheduler` | `bias_recalc_sync` | 6h |
| `WeatherSyncScheduler` | `weather_sync` / `openmeteo_forecast_sync` | 30min / 6h |
| `CacheWarmingScheduler` | `cache_warming` | 15min |
| `HistoricalBackfillScheduler` | `historical_backfill` | 20h |
| `AlertRuleEvaluator` | `alert_evaluation` | 15min |

### Cooldown System
ALL schedulers check `SyncStateService.shouldRun()` before executing.
This prevents API hammering during development restarts and enforces minimum intervals.

Admin override endpoints (ROLE_ADMIN only):
```
GET  /api/v1/admin/sync/status                      — list all job states
POST /api/v1/admin/sync/run/{jobName}               — force run immediately
POST /api/v1/admin/sync/reset-cooldown/{jobName}    — reset cooldown timer
```

### WeatherService Changes
- Uses **6 nearest nodes** (not 4), with 3D effective distance weights
- Applies **per-node bias correction** (`weather_nodes.bias_temp_c`) before IDW
- Applies **elevation lapse rate** after IDW (−0.0065°C/m)
- Checks `gn_station_anchors` first — GN divisions within 2km get real station readings
- **Rainfall fusion**: physical station (10km, ≤3h old) → JAXA satellite (±0.06°, ≤2h old) → IDW model
- **14-day forecast** from Open-Meteo with ensemble percentiles (p10/p50/p90) for days 8-14
- `getForecastWeather` now returns an `"extended"` key with days 8-14 ensemble data

### New Tables (V13 migration)

| Table | Purpose |
|-------|---------|
| `station_metadata` | 24 Meteo.gov.lk + 2 NOAA METAR stations |
| `station_observations` | Ground truth readings |
| `bias_history` | Per-node model error history (7-day rolling mean) |
| `gn_station_anchors` | GN divisions served directly from a nearby station |
| `jaxa_rain_grid` | Hourly satellite rainfall 0.1° grid |
| `node_timeseries` | Yr.no 0-72h hourly forecasts per node |
| `sync_state` | Cooldown tracking per job |
| `openmeteo_forecast_ensemble` | Ensemble percentiles for days 8-14 |

### Startup Runner
`ElevationPrecomputeRunner` (`@Order(100)`) runs once at startup to call the Open-Meteo
Elevation API and populate `elevation_m` on all weather nodes and GN division spatial units.
Idempotent — checks `sync_state` job `elevation_populated` before running.

### Do NOT
- Call external APIs from `WeatherService` or any controller
- Recompute IDW weights at runtime (pre-stored in `spatial_unit_weather_node_mappings`)
- Write to `weather_node_hourly_forecast` (deprecated — use `node_timeseries`)
- Write to `forecast_projections` or `forecast_comparisons` (AI service removed)
- Bypass `SyncStateService.shouldRun()` in any scheduler
- Add `@Autowired` field injection — constructor injection only

## Frontend Architecture

### Directory Structure

```
pages/         — Route-level: Dashboard, Map, Flood, Emergency, Operations, Reports, Analytics,
                 Notifications, Profile, Settings, Guides, FAQ, Auth, Admin (5 pages)
components/    — admin/, common/, emergency/, layout/, map/, settings/, ui/, warnings/, weather/
store/         — authStore.ts (Zustand, user persisted to localStorage)
api/           — client.ts (Axios), endpoints.ts (all endpoints), error.ts
hooks/         — useDashboardStats, useNotifications, useReports, useWeather
router/        — React Router v7 config with guards (ProtectedRoute, AdminRoute, PublicOnlyRoute)
theme/         — designTokens.ts
```

### Tech Stack
axios, zustand (auth only), @tanstack/react-query (data fetching), react-router-dom 7, react-leaflet 5 + leaflet-draw + leaflet.heat, recharts, framer-motion, lucide-react (icons only), react-hook-form + yup, react-markdown, PWA support (vite-plugin-pwa + workbox-window).

### Design System

**Dark Intelligence Dashboard** — every page is `bg-slate-900` with `bg-slate-800` cards. Full design system at `frontend/DESIGN_SYSTEM.md`. **Read it before writing any JSX.**

Key rules:
- Page backgrounds: always `bg-slate-900`
- Cards: `bg-slate-800 rounded-xl p-5 border border-slate-700 shadow-lg`
- Icons: always lucide-react, never emoji
- Fonts: system stack only (no Google Fonts)
- Gradients: only on H1 hero heading
- Severity colors: EXTREME=purple, CRITICAL=red, HIGH=orange, MODERATE=amber, LOW=blue, NORMAL=emerald

### Map Layers (5 GeoJSON, zoom-based visibility)

| Zoom Range | File | Level |
|-----------|------|-------|
| 0–5 | sl_admin0.geojson | Country |
| 6–8 | sl_admin1.geojson | Provinces |
| 9–11 | sl_admin2.geojson | Districts |
| 12–14 | sl_admin3.geojson | DS Divisions |
| 15+ | sl_admin4.geojson | GN Divisions |

### Known Frontend Issues

| Severity | Issue | Detail |
|----------|-------|--------|
| **CRITICAL** | `App.tsx` uses `bg-gray-50` instead of `bg-slate-900` | Root page background violates design system |
| **HIGH** | `UserDto` type mismatch — frontend declares 16 fields, backend `UserDto` returns only 5 | Preferences fields (unitTemp, language, theme, notifEmail, etc.) are never returned by any backend GET endpoint. Frontend settings page reads `undefined` values. |
| **HIGH** | 58 instances of `rounded-lg` on card/button elements | Should be `rounded-xl` per design system |
| **HIGH** | 5 emoji characters used instead of lucide-react icons | WeatherCard, ArcGISVectorLayers, OperationsPage |
| **MEDIUM** | `accessToken` persisted to localStorage | XSS-vulnerable. Design doc says "token in memory only" but persist middleware includes it |
| **MEDIUM** | `isAdmin()` checks for literal `'admin'` string | May not match backend role format (ROLE_ADMIN vs admin) |
| **MEDIUM** | Duplicate Button components | `components/common/Button.tsx` and `components/ui/Button.tsx` are near-duplicates |
| **MEDIUM** | API endpoints use `data: any` throughout | No TypeScript type checking on request/response shapes |
| **LOW** | 10 console.log/console.error statements left in production code | InstallPwaPrompt, NotificationsPage, SettingsPage, etc. |
| **LOW** | Gradients on non-H1 elements | AppShell brand text, avatar circles, SOS button shimmer (decorative but violates rule) |

## AI Service

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/` | Service info |
| GET | `/health` | Static health (always reports ready) |
| POST | `/predict/enhanced` | Single spatial unit forecast (SARIMAX→LSTM→XGBoost→Holt-Winters cascade) |
| POST | `/predict/multi` | Batch forecast (up to 50 units, silently skips failures) |
| POST | `/models/train` | Fake training (always returns "completed" instantly) |
| GET | `/models/status` | Training job history (in-memory dict, no cleanup) |

### Known AI Service Issues

| Severity | Issue | Detail |
|----------|-------|--------|
| **CRITICAL** | **Never called by backend** | Spring Boot has no HTTP client, no configuration, and no code that calls the AI service. Completely disconnected. |
| **CRITICAL** | **Training endpoints are fake** | `POST /models/train` creates a UUID and immediately marks everything "completed". No model is trained. |
| **HIGH** | LSTM model will almost always fail | 50 epochs from random init on CPU with tiny datasets → always triggers cascade fallback, adding latency for no benefit |
| **HIGH** | `predict_multi` silently drops failed predictions | No error reporting for which units failed or why |
| **HIGH** | `allow_origins=["*"]` with `allow_credentials=True` | Contradiction — browsers reject this combo; security risk |
| **MEDIUM** | No logging configuration | `logging()` calls exist but `basicConfig()` is never called → all logs below WARNING are dropped |
| **MEDIUM** | Confidence bounds fixed at ±15% | Doesn't grow with forecast horizon; same for all models |
| **MEDIUM** | XGBoost double-featurizes DataFrames | `add_features()` called in both ensemble.py and xgboost_model.py |
| **MEDIUM** | Unused dependencies | psycopg2-binary, redis, httpx in requirements.txt but never imported |
| **LOW** | Health endpoint always reports "ready" | Not dynamic, doesn't probe actual model state |
| **LOW** | No tests, no Docker support | Zero test files, no Dockerfile, not in docker-compose |

## Spatial Unit Hierarchy

```
COUNTRY (1) → PROVINCE (9) → DISTRICT (25) → DS_DIVISION (331) → GN_DIVISION (14,022)
Total: ~14,388 units, all with IDW mappings
PCode pattern: LK → LK1 → LK11 → LK1103 → LK1103005
GeoJSON property names: adm4_pcode→pcode, adm4_name→name, adm4_name1→name_sinhala, adm4_name2→name_tamil, center_lat→lat, center_lon→lng
```

## Redis Cache Keys and TTLs

| Key Pattern | TTL |
|-------------|-----|
| `weather:spatial:{id}` | 10 min |
| `search:locations:{query}` | 60 min |
| `warnings:active` | 5 min |
| `warnings:unit:{id}` | 5 min |
| `flood:dashboard` | 5 min |
| `analytics:overview:{id}` | 120 min |
| `meteo:content` | 15 min |
| `map:live` | 5 min |

## Environment Variables

Key `.env` variables at project root: `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_URL`, `JWT_SECRET`, `OWM_API_KEY_1/2/3`, Cloudinary credentials, `AI_SERVICE_URL`, `FRONTEND_URL`.

Frontend `.env`: `VITE_API_URL=http://localhost:8080`.

## Development Conventions

- Branches: `feature/CS-XXX-description`
- Commits: `feat(CS-XXX): description`
- Test each backend endpoint with Postman before moving on
- **Before writing frontend code:** Read `.github/copilot-instructions.md` — it mandates reading ALL backend controllers, DTOs, entities, and SecurityConfig first.
