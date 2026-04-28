import { useState, useEffect, useCallback } from 'react';
import { SpatialUnitSearch } from '../components/common/SpatialUnitSearch';
import { WeatherCard } from '../components/weather/WeatherCard';
import { WeatherHourlyStrip } from '../components/weather/WeatherHourlyStrip';
import { WeatherDailyForecast } from '../components/weather/WeatherDailyForecast';
import { WarningBanner } from '../components/warnings/WarningBanner';
import { FloodStatusWidget } from '../components/map/FloodStatusWidget';
import {
  AlertTriangle, FileText, Waves, Map as MapIcon, PlusSquare,
  BarChart2, Activity, ShieldAlert, BookOpen, ArrowRight, Clock, MapPin,
  Bookmark, CheckCircle, Navigation, Flame,
} from 'lucide-react';
import { useWeather, useNearestWeather, useActiveWarnings } from '../hooks/useWeather';
import { useDashboardStats } from '../hooks/useDashboardStats';
import { useReports } from '../hooks/useReports';
import { Link, useLocation } from 'react-router-dom';
import { formatDistanceToNow } from 'date-fns';
import DynamicLiveMiniMap from '../components/map/DynamicLiveMiniMap';
import { StatCard } from '../components/common/StatCard';
import { useAuthStore } from '../store/authStore';
import { useLocationContextStore } from '../store/locationContextStore';
import { usersApi } from '../api/endpoints';
import { zoomFromType } from '../components/map/LiveMiniMap';

interface SavedLocationItem {
  id: string;
  spatialUnitId: string;
  nickname?: string;
  spatialUnitName?: string;
  spatialUnitType?: string;
  lat?: number;
  lng?: number;
}

const quickLinks = [
  { name: 'Interactive Map', href: '/map',         icon: MapIcon,    color: 'text-blue-400 bg-blue-500/10 border-blue-500/20' },
  { name: 'New Report',      href: '/reports/new', icon: PlusSquare, color: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20' },
  { name: 'Analytics',       href: '/analytics',   icon: BarChart2,  color: 'text-purple-400 bg-purple-500/10 border-purple-500/20' },
  { name: 'Flood Monitor',   href: '/flood',        icon: Waves,      color: 'text-blue-400 bg-blue-500/10 border-blue-500/20' },
  { name: 'Emergency SOS',   href: '/emergency',   icon: ShieldAlert, color: 'text-red-400 bg-red-500/10 border-red-500/20' },
  { name: 'Safety Guides',   href: '/guides',      icon: BookOpen,   color: 'text-amber-400 bg-amber-500/10 border-amber-500/20' },
];

export default function DashboardPage() {
  const [coords, setCoords] = useState<{ lat: number; lng: number } | null>(null);
  const [selectedUnit, setSelectedUnitState] = useState<{ id: string; name: string; type: string; lat?: number; lng?: number } | null>(null);
  const [savedLocations, setSavedLocations] = useState<SavedLocationItem[]>([]);
  const [fetchingSavedLocations, setFetchingSavedLocations] = useState(false);
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [isLocating, setIsLocating] = useState(false);
  const [currentTime, setCurrentTime] = useState<Date>(new Date());
  const location = useLocation();

  const { user, isAuthenticated } = useAuthStore();
  const { selectedLocation, setSelectedLocation } = useLocationContextStore();
  const isAuth = isAuthenticated();

  // Sync with location context store on mount (for deep-link navigation)
  useEffect(() => {
    if (selectedLocation && !selectedUnit) {
      setSelectedUnitState({
        id: selectedLocation.id,
        name: selectedLocation.name,
        type: selectedLocation.type,
        lat: selectedLocation.lat,
        lng: selectedLocation.lng,
      });
    }
  }, [selectedLocation]);

  const selectedUnitId = selectedUnit?.id ?? undefined;

  // Active warnings — spatial-unit-scoped when a unit is selected, otherwise global
  const { data: warnings } = useActiveWarnings(selectedUnitId);

  const { data: stats, isLoading: statsLoading } = useDashboardStats();
  const { data: nearestWeather, isLoading: weatherLoading } = useNearestWeather(
    coords?.lat ?? null, coords?.lng ?? null
  );
  const { data: selectedWeather } = useWeather(selectedUnitId);
  const { data: recentReports } = useReports({ size: 5, sort: 'createdAt,desc' });

  // Try to get user's location silently
  useEffect(() => {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
        () => { /* Silently fail — not required */ }
      );
    }
  }, []);

  const handleLocateMe = useCallback(() => {
    if (!navigator.geolocation) {
      return;
    }

    setIsLocating(true);

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setSelectedUnitState(null);
        setSelectedLocation(null);
        setSaveState('idle');
        setIsLocating(false);
      },
      () => {
        setIsLocating(false);
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 60000,
      }
    );
  }, [setSelectedLocation]);

  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const quickSelectedUnit = (location.state as any)?.selectedUnit;
    if (quickSelectedUnit?.id) {
      setSelectedUnitState({
        id: quickSelectedUnit.id,
        name: quickSelectedUnit.name,
        type: quickSelectedUnit.type,
        lat: quickSelectedUnit.lat,
        lng: quickSelectedUnit.lng,
      });
      setSaveState('idle');
    }
  }, [location.state]);

  useEffect(() => {
    const fetchSavedLocations = async () => {
      if (!isAuth) {
        setSavedLocations([]);
        return;
      }
      try {
        setFetchingSavedLocations(true);
        const data = await usersApi.getSavedLocations();
        setSavedLocations(Array.isArray(data) ? data : []);
      } catch {
        setSavedLocations([]);
      } finally {
        setFetchingSavedLocations(false);
      }
    };

    fetchSavedLocations();
  }, [isAuth]);

  const weatherToDisplay = selectedWeather ?? nearestWeather;
  const currentTempForHourly = weatherToDisplay?.tempC ?? null;

  // Coordinates to show on MiniMap
  const mapLat = selectedUnit?.lat ?? (weatherToDisplay as any)?.lat ?? coords?.lat ?? null;
  const mapLng = selectedUnit?.lng ?? (weatherToDisplay as any)?.lng ?? coords?.lng ?? null;
  const mapZoom = selectedUnit ? zoomFromType(selectedUnit.type) : (coords ? 13 : undefined);

  // Map lat/lng from forecast context in weatherToDisplay if available
  const forecastLat = selectedUnit?.lat ?? coords?.lat ?? null;
  const forecastLng = selectedUnit?.lng ?? coords?.lng ?? null;

  const setSelectedUnit = useCallback((unit: any) => {
    setSelectedUnitState(unit);
    // Sync to global location context for cross-page consistency
    if (unit) {
      setSelectedLocation({
        id: unit.id,
        name: unit.name,
        type: unit.type,
        lat: unit.lat,
        lng: unit.lng,
      });
    }
    setSaveState('idle');
  }, [setSelectedLocation]);

  const handleSelect = useCallback((unit: any) => {
    setSelectedUnit(unit);
  }, [setSelectedUnit]);

  const handleSaveLocation = async () => {
    if (!selectedUnit || !isAuth) return;
    setSaveState('saving');
    try {
      // SavedLocationRequest DTO field: spatialUnitId
      await usersApi.addSavedLocation({ spatialUnitId: selectedUnit.id, nickname: selectedUnit.name });
      setSaveState('saved');
      const data = await usersApi.getSavedLocations();
      setSavedLocations(Array.isArray(data) ? data : []);
      setTimeout(() => setSaveState('idle'), 3000);
    } catch {
      setSaveState('error');
      setTimeout(() => setSaveState('idle'), 3000);
    }
  };

  const applySavedLocation = (locationItem: SavedLocationItem) => {
    const fallbackName =
      locationItem.nickname?.trim() ||
      locationItem.spatialUnitName?.trim() ||
      'Saved Location';
    const unit = {
      id: locationItem.spatialUnitId,
      name: fallbackName,
      type: locationItem.spatialUnitType || 'GN_DIVISION',
      lat: locationItem.lat,
      lng: locationItem.lng,
    };
    setSelectedUnitState(unit);
    // Sync to global location context
    setSelectedLocation({
      id: unit.id,
      name: unit.name,
      type: unit.type,
      lat: unit.lat,
      lng: unit.lng,
    });
    setSaveState('idle');
  };

  return (
    <div className="bg-slate-900 text-slate-100 min-h-[calc(100vh-80px)] p-6 rounded-xl border border-slate-700 font-sans space-y-6">

      {/* ── Page Header ─────────────────────────────────────────── */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-700 pb-4">
        <div>
          <h1 className="text-3xl font-black text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-emerald-400">
            Global Overview
          </h1>
          <p className="text-slate-400 flex items-center gap-2 mt-1">
            <span className="relative flex h-3 w-3">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500" />
            </span>
            Real-time situational awareness for Sri Lanka
          </p>
        </div>
        <div className="flex items-center gap-3 md:max-w-md w-full">
          <div className="hidden md:flex items-center gap-2 text-xs text-slate-300 bg-slate-800/70 border border-slate-700 rounded-lg px-3 py-2 whitespace-nowrap">
            <Clock size={14} className="text-blue-300" />
            <span>{currentTime.toLocaleTimeString()}</span>
          </div>
          <SpatialUnitSearch
            onSelect={handleSelect}
            className="flex-1"
            placeholder="Search district, city or GN division..."
          />
          <button
            type="button"
            onClick={handleLocateMe}
            disabled={isLocating}
            title="Locate me"
            className="shrink-0 p-2.5 rounded-xl border transition-all bg-slate-800 border-slate-600 text-slate-300 hover:bg-slate-700 hover:text-white disabled:opacity-60"
          >
            <Navigation size={18} className={isLocating ? 'animate-pulse text-blue-300' : ''} />
          </button>
          {/* Save location button — only when a unit is selected & authenticated */}
          {isAuth && selectedUnit && (
            <button
              onClick={handleSaveLocation}
              disabled={saveState === 'saving' || saveState === 'saved'}
              title={saveState === 'saved' ? 'Saved!' : 'Save location to profile'}
              className={`shrink-0 p-2.5 rounded-xl border transition-all ${
                saveState === 'saved'
                  ? 'bg-emerald-500/20 border-emerald-500/30 text-emerald-400'
                  : saveState === 'error'
                  ? 'bg-red-500/20 border-red-500/30 text-red-400'
                  : 'bg-slate-800 border-slate-600 text-slate-400 hover:bg-slate-700 hover:text-white'
              }`}
            >
              {saveState === 'saved'
                ? <CheckCircle size={18} />
                : <Bookmark size={18} className={saveState === 'saving' ? 'animate-pulse' : ''} />
              }
            </button>
          )}
        </div>
      </div>

      {/* ── Active Warnings ──────────────────────────────────────── */}
      {warnings && warnings.length > 0 && (
        <section>
          <div className="flex items-center gap-2 mb-3">
            <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
            <h2 className="text-sm font-bold text-red-400 uppercase tracking-widest flex items-center gap-2">
              <AlertTriangle size={14} />
              {selectedUnit
                ? `Warnings for ${selectedUnit.name} (${warnings.length})`
                : `Active Warnings (${warnings.length})`}
            </h2>
          </div>
          <WarningBanner warnings={warnings} />
        </section>
      )}

      {/* ── Main Grid ────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

        {/* Left — stats + hourly + weather card + forecast + reports */}
        <div className="lg:col-span-2 space-y-5">

          {/* Stat cards */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard title="Active Warnings" value={stats?.activeWarnings ?? 0} icon={AlertTriangle} color="text-red-400 bg-red-500/10 border-red-500/20" isLoading={statsLoading} />
            <StatCard title="Verified Reports" value={stats?.verifiedReports ?? 0} icon={FileText} color="text-blue-400 bg-blue-500/10 border-blue-500/20" isLoading={statsLoading} />
            <StatCard title="Flood Alerts" value={stats?.floodAlerts ?? 0} icon={Waves} color="text-blue-400 bg-blue-500/10 border-blue-500/20" isLoading={statsLoading} />
            <StatCard title="SOS Incidents" value={stats?.sosIncidents ?? 0} icon={Flame} color="text-orange-400 bg-orange-500/10 border-orange-500/20" isLoading={statsLoading} />
          </div>

          {/* Location context header */}
          {selectedUnit && (
            <div className="flex items-center gap-2 text-xs text-slate-400">
              <MapPin size={12} className="text-blue-400" />
              <span>Showing data for <span className="text-white font-semibold">{selectedUnit.name}</span></span>
              <span className="bg-slate-800 border border-slate-700 px-1.5 py-0.5 rounded text-[10px] uppercase">{selectedUnit.type.replace('_', ' ')}</span>
              <button onClick={() => { setSelectedUnit(null); setSaveState('idle'); }} className="ml-auto text-slate-500 hover:text-slate-300 transition text-[10px] uppercase tracking-wide">
                Clear →
              </button>
            </div>
          )}

          {/* Hourly forecast strip */}
          {(forecastLat || coords) && (
            <section>
              <h2 className="text-sm font-bold flex items-center gap-2 mb-3 text-white/60 uppercase tracking-widest">
                <Activity size={14} /> Hourly Outlook
              </h2>
              <WeatherHourlyStrip
                lat={forecastLat}
                lng={forecastLng}
                currentTempC={currentTempForHourly}
              />
            </section>
          )}

          {/* Weather card */}
          <section>
            <h2 className="text-lg font-bold flex items-center gap-2 mb-4 text-emerald-400">
              <Activity size={18} /> Local Environment
            </h2>
            <div className="rounded-xl overflow-hidden">
              {weatherLoading ? (
                <div className="h-48 flex items-center justify-center bg-slate-800 rounded-xl border border-slate-700">
                  <div className="flex flex-col items-center gap-3">
                    <Clock className="text-slate-500 animate-spin" size={28} />
                    <p className="text-xs text-slate-500 font-bold uppercase tracking-widest">Acquiring Data...</p>
                  </div>
                </div>
              ) : weatherToDisplay ? (
                <WeatherCard data={weatherToDisplay as any} />
              ) : (
                <div className="h-48 flex items-center justify-center rounded-xl border border-dashed border-slate-700">
                  <div className="flex flex-col items-center text-center p-8">
                    <MapPin className="mb-3 text-slate-700" size={36} />
                    <p className="text-sm text-slate-400 italic">
                      Search a location or enable GPS to see weather data.
                    </p>
                  </div>
                </div>
              )}
            </div>
          </section>

          {/* 7-day forecast — only when we have forecast coordinates */}
          {(forecastLat != null && forecastLng != null) && (
            <section>
              <WeatherDailyForecast lat={forecastLat} lng={forecastLng} />
            </section>
          )}

          {/* Recent reports */}
          <section>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold flex items-center gap-2 text-blue-300">
                <ShieldAlert size={18} /> Community Activity
              </h2>
              <Link to="/reports" className="text-[10px] font-bold text-blue-400 uppercase tracking-widest hover:text-blue-300 transition flex items-center gap-1">
                View All <ArrowRight size={10} />
              </Link>
            </div>
            <div className="space-y-3">
              {recentReports?.content?.map((report: any) => (
                <div key={report.id} className="bg-slate-800 border border-slate-700 rounded-xl p-4 flex gap-4 hover:border-slate-600 transition">
                  <div className={`shrink-0 w-11 h-11 rounded-xl flex items-center justify-center ${
                    report.category === 'FLOOD' ? 'bg-blue-500/10 text-blue-400' :
                    report.category === 'WEATHER' ? 'bg-emerald-500/10 text-emerald-400' :
                    'bg-slate-700 text-slate-400'
                  }`}>
                    <FileText size={18} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex justify-between items-start">
                      <h4 className="text-sm font-bold text-slate-200 truncate">{report.description || report.category}</h4>
                      <span className="text-[10px] text-slate-500 whitespace-nowrap ml-2">
                        {formatDistanceToNow(new Date(report.createdAt), { addSuffix: true })}
                      </span>
                    </div>
                    <p className="text-xs mt-1 flex items-center gap-1 text-slate-400">
                      <MapPin size={11} /> {report.spatialUnitName || 'Unknown Location'}
                    </p>
                  </div>
                </div>
              ))}
              {(!recentReports?.content || recentReports.content.length === 0) && (
                <p className="text-sm text-slate-400 italic">No activity yet.</p>
              )}
            </div>
          </section>
        </div>

        {/* Right — mini map + flood status + quick actions + emergency hub */}
        <div className="space-y-5">

          {/* Live Mini-Map — focused on searched/located point */}
          <section>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold flex items-center gap-2 text-emerald-400">
                <MapIcon size={18} /> Live Mini-Map
              </h2>
              <span className="relative flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500" />
              </span>
            </div>
            <div className="bg-slate-800 rounded-xl border border-slate-700 shadow-lg p-1 overflow-hidden h-64">
              <DynamicLiveMiniMap
                lat={mapLat}
                lng={mapLng}
                zoom={mapZoom}
                locationName={selectedUnit?.name}
              />
            </div>
            {selectedUnit && (
              <p className="text-[10px] text-slate-500 mt-1 text-center flex items-center justify-center gap-1">
                <Navigation size={9} /> Focused on {selectedUnit.name}
              </p>
            )}
          </section>

          {/* Flood Status Widget */}
          <section>
            <h2 className="text-sm font-bold flex items-center gap-2 mb-3 text-blue-300 uppercase tracking-widest">
              <Waves size={14} /> Flood Intelligence
            </h2>
            <FloodStatusWidget />
          </section>

          {/* Saved Locations */}
          {isAuth && (
            <section>
              <h2 className="text-sm font-bold flex items-center gap-2 mb-3 text-emerald-400 uppercase tracking-widest">
                <Bookmark size={14} /> Saved Locations
              </h2>
              <div className="bg-slate-800 border border-slate-700 rounded-xl p-3 space-y-2">
                {fetchingSavedLocations ? (
                  <p className="text-xs text-slate-500">Loading saved locations...</p>
                ) : savedLocations.length === 0 ? (
                  <p className="text-xs text-slate-500 italic">Save a location from the search bar to pin it here.</p>
                ) : (
                  savedLocations.map((item) => (
                    <button
                      key={item.id}
                      onClick={() => applySavedLocation(item)}
                      className="w-full text-left px-3 py-2 rounded-xl bg-slate-900/70 border border-slate-700 hover:border-emerald-500/40 hover:text-white text-slate-300 transition"
                    >
                      <span className="text-sm font-semibold">{item.nickname || item.spatialUnitName || 'Saved Location'}</span>
                      {item.spatialUnitType ? (
                        <span className="ml-2 text-[10px] uppercase text-slate-500">{item.spatialUnitType.replace('_', ' ')}</span>
                      ) : null}
                    </button>
                  ))
                )}
              </div>
            </section>
          )}

          {/* Quick Actions */}
          <section>
            <h2 className="text-lg font-bold flex items-center gap-2 mb-4 text-blue-300">
              <BarChart2 size={18} /> Quick Actions
            </h2>
            <div className="grid grid-cols-2 gap-3">
              {quickLinks.map((link) => (
                <Link
                  key={link.name}
                  to={link.href}
                  className="flex flex-col items-center justify-center p-4 bg-slate-800 border border-slate-700 rounded-xl hover:border-slate-600 hover:bg-slate-750 transition group text-center"
                >
                  <div className={`p-3 rounded-xl transition-transform group-hover:scale-110 mb-2 border ${link.color}`}>
                    <link.icon size={20} />
                  </div>
                  <span className="text-xs font-bold text-slate-300 group-hover:text-slate-100 transition">{link.name}</span>
                </Link>
              ))}
            </div>
          </section>

          {/* Emergency Hub */}
          <div className="bg-red-900/20 border border-red-500/30 rounded-xl p-5 relative overflow-hidden">
            <div className="absolute -right-3 -bottom-3 text-red-500/10 rotate-12">
              <ShieldAlert size={100} />
            </div>
            <h3 className="text-lg font-bold flex items-center gap-2 mb-1 text-red-400">
              <ShieldAlert size={18} /> Emergency Hub
            </h3>
            <p className="text-xs text-red-300/70 leading-relaxed mb-4 relative z-10">
              Facing an immediate threat? Use our high-priority SOS channel to alert responders instantly.
            </p>
            <Link
              to="/emergency"
              className="inline-flex items-center gap-2 bg-red-600 hover:bg-red-500 text-white text-[10px] font-black px-4 py-2 rounded-lg transition shadow-lg"
            >
              OPEN HUB <ArrowRight size={12} />
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}