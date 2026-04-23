# FRONTEND AGENTS: READ THE BACKEND FIRST

Before writing ANY frontend code, you MUST:

1. Read backend/src/main/java/com/climasphere/backend/controller/ — read EVERY controller file to get exact endpoint URLs, HTTP methods, and request/response shapes
2. Read backend/src/main/java/com/climasphere/backend/dto/ — read EVERY DTO file to get exact field names, types, and nesting
3. Read backend/src/main/java/com/climasphere/backend/entity/ — check entity field names (they map to DTO fields)
4. Read backend/src/main/java/com/climasphere/backend/security/SecurityConfig.java — check which endpoints are public vs require auth vs require ROLE_ADMIN

NEVER assume an endpoint URL, field name, or response shape.
NEVER invent field names like "name" if the DTO says "displayName".
NEVER call an endpoint that does not exist in the controllers.
If a controller file is missing, tell the user before writing frontend code.

# ClimaSphere – AI Coding Agent Instructions

## Project
ClimaSphere is a Sri Lanka disaster management and weather intelligence platform.
Stack: Spring Boot 3.2 (Java 21), React Vite TypeScript, PostgreSQL 16, Redis 7, Python FastAPI.
Old Node.js project is domain logic reference ONLY. Never copy code from it.

## Backend Rules
- Package: com.climasphere.backend
- All entities: UUID PKs with @GeneratedValue(strategy=GenerationType.AUTO) and @Column(columnDefinition="uuid DEFAULT uuid_generate_v4()")
- Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor on ALL entities
- Spring Data JPA repositories extending JpaRepository<Entity, UUID>
- @Transactional on ALL service methods that write data
- BCrypt strength 12 for passwords, strength 10 for refresh tokens
- JWT access token 15 min expiry, refresh token 30 days (HttpOnly Secure SameSite=Strict cookie)
- All custom exceptions extend RuntimeException
- GlobalExceptionHandler with @RestControllerAdvice maps exceptions to HTTP statuses
- @Cacheable with explicit cache names; invalidate with @CacheEvict or RedisTemplate.delete()

## Database
- Flyway migrations in src/main/resources/db/migration/
- V1=enums, V2=users/auth, V3=spatial/weather, V4=disaster/flood, V5=analytics/content, V6=postgis
- All tables: UUID PKs, created_at TIMESTAMP DEFAULT NOW(), explicit FK names

## CRITICAL Architecture Rules
- Weather is ALWAYS interpolated (IDW) from 668 weather nodes. NEVER call external APIs per user request.
- ALL spatial unit levels (Country, Province, District, DS Division, GN Division) have IDW mappings in spatial_unit_weather_node_mappings. Total ~14,388 units all have mappings.
- User requests NEVER call external APIs. All data from DB/Redis.
- External API calls ONLY in scheduled jobs.
- IDW is pre-computed once at setup. Stored in DB. Used at query time as simple weighted average lookup.
- disaster_warnings has NO spatial_unit_id column. Uses warning_spatial_units join table (multi-target, any level mix).
- Weather and analytics endpoints work for ANY spatial_unit_id regardless of type.

## Spatial Unit Hierarchy
- COUNTRY (1) → PROVINCE (9) → DISTRICT (25) → DS_DIVISION (331) → GN_DIVISION (14,022)
- Total: ~14,388 units, all with IDW mappings
- PCode pattern: LK → LK1 → LK11 → LK1103 → LK1103005
- GeoJSON property names from COD-AB files:
  adm4_pcode → pcode
  adm4_name → name
  adm4_name1 → name_sinhala
  adm4_name2 → name_tamil
  center_lat → lat
  center_lon → lng
- When resolving warnings for a unit: check direct match AND all ancestors up the tree

## Warning Targeting
- disaster_warnings has NO spatial_unit_id
- warning_spatial_units join table links warnings to spatial units (any level, multiple units per warning)
- A Country warning (pcode=LK) applies to every polygon on the map
- A District warning applies to all DS divisions and GN divisions within it
- getActiveWarningsForSpatialUnit() walks UP the parent chain to find ancestor warnings

## Redis Cache Keys and TTLs
- weather:spatial:{id} → 10 min
- search:locations:{query} → 60 min
- warnings:active → 5 min
- warnings:unit:{id} → 5 min
- flood:dashboard → 5 min
- analytics:overview:{id} → 120 min
- meteo:content → 15 min
- map:live → 5 min

## Frontend Rules
- Zustand auth store: user + accessToken persisted to localStorage (note: token in localStorage is XSS-vulnerable, known issue)
- Axios: request interceptor adds JWT Bearer header, response interceptor handles 401 → refresh rotation
- React Router v7: ProtectedRoute, AdminRoute, PublicOnlyRoute components
- Tailwind CSS breakpoints: sm:640 md:768 lg:1024 xl:1280
- API endpoints defined in frontend/src/api/endpoints.ts — ALL use `data: any` (no TS type safety yet)
- Two near-duplicate Button components exist: components/common/Button.tsx and components/ui/Button.tsx
- Map uses 5 GeoJSON layers (ADM0-ADM4) with zoom-based visibility:
  Zoom 0-5: sl_admin0.geojson (Country)
  Zoom 6-8: sl_admin1.geojson (Provinces)
  Zoom 9-11: sl_admin2.geojson (Districts)
  Zoom 12-14: sl_admin3.geojson (DS Divisions)
  Zoom 15+: sl_admin4.geojson (GN Divisions)
- Warning colouring: check if polygon pcode matches any targeted unit OR any of its ancestors

## GeoJSON Files
- Backend import: backend/src/main/resources/geodata/lka_admin0.geojson to lka_admin4.geojson
- Frontend map: frontend/public/geodata/sl_admin0.geojson to sl_admin4.geojson

## Git Workflow
- Branches: feature/CS-XXX-description
- Commits: feat(CS-XXX): description
- Every AI prompt starts with: "Read .github/copilot-instructions.md for project context, then:"
- Test each backend task with Postman before moving on

## Frontend Design System

> This section is MANDATORY for all frontend tasks. Read it completely before writing any JSX.
> The full design system is at frontend/DESIGN_SYSTEM.md — read that file too.

### Design Language: Dark Intelligence Dashboard
Every screen must feel like a tactical operations center. Dark. Precise. Alive.
This design was extracted directly from the ReferenceLook reference project.

### Non-Negotiable Rules

1. **Page background is ALWAYS `bg-slate-900`** — never white, never light
2. **Cards are ALWAYS** `bg-slate-800 rounded-xl p-5 border border-slate-700 shadow-lg`
3. **Inputs are ALWAYS** `border border-slate-600 bg-slate-700 text-slate-100 placeholder-slate-400 p-2 rounded focus:ring-2 focus:ring-blue-500 outline-none`
4. **Primary button is ALWAYS** `bg-blue-600 text-white font-bold py-2 rounded hover:bg-blue-500 transition`
5. **Secondary button is ALWAYS** `bg-slate-800 hover:bg-slate-700 text-slate-200 px-4 py-2 flex items-center gap-2 rounded-lg font-semibold transition border border-slate-600`
6. **Section headings ALWAYS** use `text-lg font-bold flex items-center gap-2 mb-4` + an icon from lucide-react + one of these accent colors: `text-blue-300` (data/map), `text-amber-400` (warnings), `text-purple-400` (analytics), `text-emerald-400` (live/status), `text-red-400` (danger)
7. **H1 page title ALWAYS** uses `text-3xl font-black text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-emerald-400`
8. **Gradient is ONLY allowed on H1** — never on buttons, cards, or body text
9. **All icons from lucide-react** — never emoji, never other icon libraries
10. **Font is ALWAYS `font-sans`** — no Google Fonts, no @import, no custom fonts
11. **ALL clickable elements have `transition` class**
12. **Alert panels use transparency overlays**: critical=`bg-red-900/40 border-red-500/50 text-red-400`, warning=`bg-amber-900/40 border-amber-500/50 text-amber-400`, info=`bg-blue-900/40 border-blue-500/50 text-blue-400`

### Severity Color Map

| Severity | Panel bg | Border | Text |
|---|---|---|---|
| EXTREME | bg-purple-900/40 | border-purple-500/50 | text-purple-400 |
| CRITICAL | bg-red-900/40 | border-red-500/50 | text-red-400 |
| HIGH | bg-orange-900/40 | border-orange-500/50 | text-orange-400 |
| MODERATE | bg-amber-900/40 | border-amber-500/50 | text-amber-400 |
| LOW | bg-blue-900/40 | border-blue-500/50 | text-blue-400 |
| NORMAL | bg-emerald-900/40 | border-emerald-500/50 | text-emerald-400 |

### Live Status Indicator (use wherever data is real-time)

```jsx
<span className="relative flex h-3 w-3">
  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
  <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500"></span>
</span>
```

### Page Template (use for EVERY page)

```jsx
<div className="bg-slate-900 text-slate-100 min-h-[calc(100vh-80px)] p-6 rounded-xl border border-slate-700 font-sans">
  <div className="flex justify-between items-center mb-6 border-b border-slate-700 pb-4">
    <div>
      <h1 className="text-3xl font-black text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-emerald-400">
        Page Title
      </h1>
      <p className="text-slate-400 flex items-center gap-2 mt-1">
        {/* pulsing dot + subtitle */}
      </p>
    </div>
    <div className="flex gap-4">{/* action buttons */}</div>
  </div>
  <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
    {/* sidebar: lg:col-span-1 */}
    {/* main: lg:col-span-3 */}
  </div>
</div>
```

### Status Badge Pills

```jsx
// active/online:  bg-emerald-500/20 text-emerald-400 border border-emerald-500/30
// risk/critical:  bg-red-500/20 text-red-400 border border-red-500/30
// warning:        bg-amber-500/20 text-amber-400 border border-amber-500/30
// info/neutral:   bg-blue-500/20 text-blue-400 border border-blue-500/30
// all add:        px-2 py-1 rounded text-xs
```

### Empty State

```jsx
<p className="text-sm text-slate-400 italic">No data available at this time.</p>
```

### Loading Skeleton

```jsx
<div className="animate-pulse space-y-3">
  <div className="h-4 bg-slate-700 rounded w-3/4"></div>
  <div className="h-4 bg-slate-700 rounded w-1/2"></div>
</div>
```

## Known Issues (as of 2026-04-03)

Do not re-introduce these issues. When fixing one, remove the relevant line from this section.

### Backend
- `DisasterCategory.OTHER` in Java enum but NOT in V1 DB migration enum (persisting OTHER will fail with constraint violation)
- `User` entity uses `FetchType.EAGER` for roles (N+1 on user listing)
- Two confusingly-named alert evaluators: `AlertEngineService` (DMC raw warnings, 5 min) vs `AlertRuleEvaluator` (user alert rules, 15 min)
- Tables exist in migrations but have NO JPA entities: district_severity_cache, mass_alerts, anomaly_scores, dmc_raw_warnings, api_usage_logs
- No "forgot password" flow or email verification
- Zero test classes exist
- `WeatherNodeTelemetryLog` uses Long IDENTITY (inconsistent with UUID pattern)
- Some DTO/Entity size mismatches: SpatialUnitDto.name validates max 200 but entity is VARCHAR(255); WeatherNodeDto.code validates max 64 but entity is VARCHAR(50)
- `AlertRule.createdAt` never auto-set (no @PrePersist)
- `FloodSyncScheduler` intervals are hardcoded 900000 instead of using `${app.sync.flood.interval}`
- Flyway migration V10 is missing (sequence goes V1-V9, V11)
- `seed_demo_data.sql` referenced but does not exist on disk

### Frontend
- `App.tsx` uses `bg-gray-50` for root background (should be `bg-slate-900`)
- `UserDto` frontend type declares 16 fields but backend GET /api/v1/users/me returns only 5 (preferences fields are never returned, always `undefined`)
- 58 instances of `rounded-lg` on cards/buttons (should be `rounded-xl`)
- 5 emoji characters used instead of lucide-react icons (WeatherCard, ArcGISVectorLayers, OperationsPage)
- `accessToken` persisted to localStorage (XSS-vulnerable, known issue)
- `isAdmin()` checks for literal `'admin'` string (fragile — depends on exact backend role format)
- Duplicate Button components (common/Button.tsx + ui/Button.tsx)
- 10 console.log/console.error statements in production code
- Raw `fetch()` in MapPage.tsx bypasses auth interceptor
- Multiple empty catch blocks silently swallowing errors (MapPage.tsx, ArcGISVectorLayers.tsx)
- API endpoints all use `data: any` (no TypeScript type safety)

## Weather Engine Components (V13+)

### Cooldown Pattern (MANDATORY for all schedulers)

Every `@Scheduled` method MUST start with:
```java
if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
```
And wrap the work body in try/catch calling `recordSuccess` or `recordFailure`:
```java
try {
    doSync();
    syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
} catch (Exception e) {
    log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
    syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
}
```
See `SyncStateService` for the full API.
See `YrNoSyncScheduler` for a complete implementation example.
The `@Scheduled` method must be private/package and call a **public** `doSync()` method
so `AdminSyncController` can trigger it directly.

### New Tables to Know About

| Table | Key Repository Method |
|-------|----------------------|
| `node_timeseries` | `findTopByNodeIdAndForecastHourOrderByValidFromUtcDesc(nodeId, 0)` — gets the most recent hour-0 snapshot (current conditions) for a node |
| `station_observations` | Ground truth from 24 Meteo.gov.lk + 2 NOAA METAR stations |
| `gn_station_anchors` | GN divisions served station readings directly (within 2km) |
| `jaxa_rain_grid` | Hourly satellite rainfall 0.1° grid — query by lat/lon bbox + timestamp |
| `sync_state` | **Never query/modify directly** — use `SyncStateService` only |
| `bias_history` | Per-node model error history; 7-day rolling mean → `weather_nodes.bias_temp_c` |
| `openmeteo_forecast_ensemble` | Days 8-14 ensemble percentiles (p10/p50/p90) per node |

### WeatherService Priority Chain

**Current weather (temperature, humidity, etc.):**
1. `gn_station_anchors` → if the requested GN division is anchored to a station within 2km, serve station reading directly with quality label `STATION_DIRECT`
2. IDW from `node_timeseries` (6 nearest nodes, 3D effective-distance weights, bias-corrected, lapse-rate adjusted) with quality label `MODEL_BIAS_CORRECTED`

**Rainfall specifically:**
1. Physical station within 10km with observation ≤3h old
2. JAXA GSMaP satellite grid within ±0.06° with timestamp ≤2h old
3. IDW-weighted precipitation from `node_timeseries`

**14-day forecast:**
- Days 1-7: standard Open-Meteo daily payload (`"daily"` key)
- Days 8-14: ensemble percentiles from `openmeteo_forecast_ensemble` (`"extended"` key)
  - Each entry: `{ date, tempMin: {p10,p50,p90}, tempMax: {p10,p50,p90}, rainProbability, confidence: "LOW" }`

### Package: com.sidms.backend (not com.climasphere.backend)

> **IMPORTANT:** The package was renamed. All imports must use `com.sidms.backend`.
> References to `com.climasphere.backend` in this file are outdated — ignore them for backend tasks.

### Scheduler Job Names (for AdminSyncController)

```
POST /api/v1/admin/sync/run/yrno_sync
POST /api/v1/admin/sync/run/openmeteo_forecast_sync
POST /api/v1/admin/sync/run/meteo_gov_lk_sync
POST /api/v1/admin/sync/run/noaa_metar_sync
POST /api/v1/admin/sync/run/jaxa_gsmap_sync
POST /api/v1/admin/sync/run/bias_recalc_sync
POST /api/v1/admin/sync/run/weather_sync
POST /api/v1/admin/sync/run/flood_sync
POST /api/v1/admin/sync/run/meteo_content_sync
POST /api/v1/admin/sync/run/cache_warming
POST /api/v1/admin/sync/run/historical_backfill
POST /api/v1/admin/sync/run/alert_evaluation
GET  /api/v1/admin/sync/status
POST /api/v1/admin/sync/reset-cooldown/{jobName}
```

### Deprecated — Do NOT Write To
- `weather_node_hourly_forecast` → superseded by `node_timeseries`
- `forecast_projections` → AI service disconnected, no new records
- `forecast_comparisons` → same as above

