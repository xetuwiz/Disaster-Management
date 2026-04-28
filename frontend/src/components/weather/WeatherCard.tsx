import React from 'react';
import {
  Cloud, CloudDrizzle, CloudFog, CloudLightning, CloudRain, CloudSnow, CloudSun,
  Sun, Moon, Wind, Droplets, Thermometer, Eye, Activity, Compass, Gauge,
  Sunrise as SunriseIcon, Sunset as SunsetIcon, Zap
} from 'lucide-react';

// ── Types ────────────────────────────────────────────────────────────────────

export interface WeatherResponse {
  spatialUnitId: string;
  spatialUnitName: string;
  spatialUnitType: string;
  tempC: number;
  apparentTempC: number;
  dewPointC?: number;
  humidityPct: number;
  pressureHpa?: number;
  visibilityM?: number;
  precipitationMm: number;
  precipProbability?: number;
  windSpeedKmh: number;
  windGustKmh?: number;
  windDirectionDeg?: number;
  cloudCoverPct?: number;
  uvIndex?: number;
  capeJkg?: number;
  weatherCode: number;
  isDay?: number;
  usAqi?: number;
  pm10?: number;
  pm25?: number;
  sunrise?: string;
  sunset?: string;
  fetchedAt?: string;
  dataQuality?: string;
}

// ── Helpers ──────────────────────────────────────────────────────────────────

export const getWeatherIcon = (code: number, isDay = 1, className = 'w-8 h-8') => {
  if (code === 0) return isDay ? <Sun className={className} /> : <Moon className={className} />;
  if (code >= 1 && code <= 2) return isDay ? <CloudSun className={className} /> : <Cloud className={className} />;
  if (code === 3) return <Cloud className={className} />;
  if (code >= 45 && code <= 48) return <CloudFog className={className} />;
  if (code >= 51 && code <= 57) return <CloudDrizzle className={className} />;
  if (code >= 61 && code <= 67) return <CloudRain className={className} />;
  if (code >= 71 && code <= 77) return <CloudSnow className={className} />;
  if (code >= 80 && code <= 82) return <CloudRain className={className} />;
  if (code >= 85 && code <= 86) return <CloudSnow className={className} />;
  if (code >= 95 && code <= 99) return <CloudLightning className={className} />;
  return <Cloud className={className} />;
};

export const getConditionText = (code: number): string => {
  const map: Record<number, string> = {
    0: 'Clear Sky', 1: 'Mainly Clear', 2: 'Partly Cloudy', 3: 'Overcast',
    45: 'Foggy', 48: 'Rime Fog',
    51: 'Light Drizzle', 53: 'Moderate Drizzle', 55: 'Dense Drizzle',
    61: 'Slight Rain', 63: 'Moderate Rain', 65: 'Heavy Rain',
    71: 'Slight Snow', 73: 'Moderate Snow', 75: 'Heavy Snow', 77: 'Snow Grains',
    80: 'Rain Showers', 81: 'Moderate Showers', 82: 'Violent Showers',
    85: 'Snow Showers', 86: 'Heavy Snow Showers',
    95: 'Thunderstorm', 96: 'Thunderstorm + Hail', 99: 'Thunderstorm + Heavy Hail',
  };
  return map[code] ?? 'Unknown';
};

export const mapSymbolToWmo = (sym?: string): number => {
  if (!sym) return 3; // Default to cloudy
  const s = sym.toLowerCase();
  if (s.includes('clear') || s.includes('fair') || s.includes('sun')) return 0;
  if (s.includes('partlycloudy')) return 2;
  if (s.includes('cloudy')) return 3;
  if (s.includes('fog')) return 45;
  if (s.includes('lightrain')) return 51;
  if (s.includes('heavyrain')) return 65;
  if (s.includes('rainshowers')) return 80;
  if (s.includes('rain')) return 63;
  if (s.includes('snow')) return 73;
  if (s.includes('lightning') || s.includes('thunderstorm')) return 95;
  return 3;
};


const getUvLabel = (uv: number) => {
  if (uv <= 2) return { label: 'Low', color: 'from-emerald-400 to-emerald-500', text: 'text-emerald-400' };
  if (uv <= 5) return { label: 'Moderate', color: 'from-yellow-400 to-amber-500', text: 'text-yellow-400' };
  if (uv <= 7) return { label: 'High', color: 'from-orange-500 to-red-500', text: 'text-orange-400' };
  if (uv <= 10) return { label: 'Very High', color: 'from-red-600 to-rose-600', text: 'text-red-400' };
  return { label: 'Extreme!', color: 'from-red-700 to-purple-600', text: 'text-purple-400' };
};

const getAqiLabel = (aqi: number) => {
  if (aqi <= 50) return { label: 'Good', color: 'from-emerald-400 to-emerald-500', text: 'text-emerald-400' };
  if (aqi <= 100) return { label: 'Moderate', color: 'from-yellow-400 to-yellow-500', text: 'text-yellow-400' };
  if (aqi <= 150) return { label: 'Unhealthy †', color: 'from-orange-400 to-orange-500', text: 'text-orange-400' };
  if (aqi <= 200) return { label: 'Unhealthy', color: 'from-red-500 to-red-600', text: 'text-red-400' };
  return { label: 'Very Unhealthy', color: 'from-purple-500 to-purple-700', text: 'text-purple-400' };
};

const getPressureLabel = (hpa: number) =>
  hpa > 1020 ? 'High' : hpa < 1005 ? 'Low' : 'Normal';

const getWindDir = (deg: number) => {
  const dirs = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  return dirs[Math.round(deg / 45) % 8];
};

const formatSolarTime = (iso?: string) => {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso.slice(-5) : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
};

// ── Mini stat tile ────────────────────────────────────────────────────────────

interface TileProps { icon: React.ReactNode; label: string; children: React.ReactNode; accent?: string; }
const Tile: React.FC<TileProps> = ({ icon, label, children, accent = 'text-blue-400' }) => (
  <div className="flex flex-col gap-2 p-4 bg-white/5 rounded-2xl border border-white/5 hover:bg-white/10 transition-colors backdrop-blur-md">
    <div className="flex items-center gap-2">
      <span className={`${accent} drop-shadow-sm`}>{icon}</span>
      <p className="text-[10px] uppercase font-bold text-white/50 tracking-wider">{label}</p>
    </div>
    {children}
  </div>
);

// ── Progress bar helper ───────────────────────────────────────────────────────

const Bar: React.FC<{ value: number; max: number; gradient: string }> = ({ value, max, gradient }) => (
  <div className="w-full h-1.5 rounded-full overflow-hidden bg-white/10 mt-1">
    <div
      className={`h-full rounded-full bg-gradient-to-r ${gradient} transition-all duration-700`}
      style={{ width: `${Math.min((value / max) * 100, 100)}%` }}
    />
  </div>
);

// ── Main Card ─────────────────────────────────────────────────────────────────

export const WeatherCard: React.FC<{ data: WeatherResponse }> = ({ data }) => {
  const uvMeta = getUvLabel(data.uvIndex ?? 0);
  const aqiMeta = data.usAqi != null ? getAqiLabel(data.usAqi) : null;
  const isDay = data.isDay ?? 1;

  return (
    <div className="relative overflow-hidden rounded-3xl p-6 shadow-[0_8px_32px_0_rgba(0,0,0,0.35)]
                    bg-white/5 backdrop-blur-xl border border-white/10 group transition-all duration-300
                    hover:bg-white/8 hover:shadow-[0_8px_40px_0_rgba(0,0,0,0.45)]">

      {/* Ambient gradient glows */}
      <div className="absolute -top-24 -right-24 w-72 h-72 bg-blue-500/15 rounded-full mix-blend-screen filter blur-[90px] opacity-60 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none" />
      <div className="absolute -bottom-24 -left-24 w-72 h-72 bg-emerald-500/15 rounded-full mix-blend-screen filter blur-[90px] opacity-60 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none" />



      {/* ── Header ── */}
      <div className="relative z-10 flex justify-between items-center gap-6 mb-8">
        <div className="flex items-center gap-5">
          <div className="relative flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-white/10 to-transparent border border-white/20 shadow-lg shrink-0">
            <span className="text-blue-300 drop-shadow-lg">
              {getWeatherIcon(data.weatherCode, isDay, 'w-12 h-12')}
            </span>
          </div>
          <div>
            <h2 className="text-2xl font-bold text-white/90 flex items-center gap-3 drop-shadow-sm">
              <span className="relative flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.8)]" />
            </span>
            {data.spatialUnitName}
          </h2>
          <p className="text-white/50 text-sm mt-1 font-medium tracking-wide flex items-center gap-2">
            {getConditionText(data.weatherCode)}
            {data.dataQuality && (
              <span
                className={`text-[9px] font-bold uppercase tracking-widest px-1.5 py-0.5 rounded border ${
                  data.dataQuality === 'LIVE' || data.dataQuality === 'STATION_DIRECT'
                    ? 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30'
                    : data.dataQuality === 'MODEL_BIAS_CORRECTED'
                    ? 'bg-blue-500/20 text-blue-400 border-blue-500/30'
                    : data.dataQuality === 'ESTIMATED'
                    ? 'bg-amber-500/20 text-amber-400 border-amber-500/30'
                    : 'bg-slate-500/20 text-slate-400 border-slate-500/30'
                }`}
              >
                {data.dataQuality}
              </span>
            )}
          </p>
          </div>
        </div>
        <div className="text-right">
          <div className="text-5xl font-extrabold text-white tracking-tighter drop-shadow-md">
            {data.tempC != null ? Math.round(data.tempC) : '—'}°
          </div>
          <div className="text-sm text-white/50 font-medium mt-1">
            Feels {data.apparentTempC != null ? Math.round(data.apparentTempC) : '—'}°
          </div>
        </div>
      </div>

      {/* ── Row 1: Core metrics ── */}
      <div className="relative z-10 grid grid-cols-2 lg:grid-cols-4 gap-3 mb-3">

        {/* Humidity */}
        <Tile icon={<Droplets size={15} />} label="Humidity" accent="text-blue-400">
          <p className="text-lg font-semibold text-white/90">
            {data.humidityPct != null ? Math.round(data.humidityPct) : '—'}<span className="text-xs text-white/40 ml-0.5">%</span>
          </p>
          {data.dewPointC != null && (
            <p className="text-[10px] text-white/40">Dew {Math.round(data.dewPointC)}°C</p>
          )}
        </Tile>

        {/* Wind */}
        <Tile icon={<Wind size={15} />} label="Wind" accent="text-cyan-400">
          <p className="text-lg font-semibold text-white/90">
            {data.windSpeedKmh != null ? Math.round(data.windSpeedKmh) : '—'} <span className="text-[10px] text-white/40">km/h</span>
          </p>
          <p className="text-[10px] text-white/40 flex items-center gap-1">
            {data.windDirectionDeg != null && (
              <>
                <Compass size={10} style={{ transform: `rotate(${data.windDirectionDeg}deg)` }} />
                {getWindDir(data.windDirectionDeg)} {data.windGustKmh != null && `· Gust ${Math.round(data.windGustKmh)}`}
              </>
            )}
          </p>
        </Tile>

        {/* Precipitation */}
        <Tile icon={<CloudRain size={15} />} label="Precip" accent="text-indigo-400">
          <p className="text-lg font-semibold text-white/90">
            {data.precipitationMm != null ? data.precipitationMm.toFixed(1) : '—'} <span className="text-[10px] text-white/40">mm</span>
          </p>
          {data.precipProbability != null && (
            <p className="text-[10px] text-white/40">{Math.round(data.precipProbability)}% probability</p>
          )}
        </Tile>

        {/* UV Index */}
        <Tile icon={<Sun size={15} />} label="UV Index" accent="text-amber-400">
          <p className={`text-lg font-semibold ${uvMeta.text}`}>{data.uvIndex != null ? Math.round(data.uvIndex) : '—'}</p>
          {data.uvIndex != null && (
            <>
              <p className={`text-[10px] ${uvMeta.text}`}>{uvMeta.label}</p>
              <Bar value={data.uvIndex} max={11} gradient={uvMeta.color} />
            </>
          )}
        </Tile>
      </div>

      {/* ── Row 2: Extended metrics ── */}
      <div className="relative z-10 grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">

        {/* Pressure */}
        <Tile icon={<Gauge size={15} />} label="Pressure" accent="text-sky-400">
          <p className="text-lg font-semibold text-white/90">
            {data.pressureHpa != null ? Math.round(data.pressureHpa) : '—'} <span className="text-[10px] text-white/40">hPa</span>
          </p>
          {data.pressureHpa != null && (
            <p className="text-[10px] text-white/40">{getPressureLabel(data.pressureHpa)}</p>
          )}
        </Tile>

        {/* Cloud Cover */}
        <Tile icon={<Cloud size={15} />} label="Cloud Cover" accent="text-slate-300">
          <p className="text-lg font-semibold text-white/90">
            {data.cloudCoverPct != null ? Math.round(data.cloudCoverPct) : '—'}<span className="text-[10px] text-white/40 ml-0.5">%</span>
          </p>
          {data.cloudCoverPct != null && <Bar value={data.cloudCoverPct} max={100} gradient="from-slate-400 to-slate-200" />}
        </Tile>

        {/* Visibility */}
        <Tile icon={<Eye size={15} />} label="Visibility" accent="text-teal-400">
          <p className="text-lg font-semibold text-white/90">
            {data.visibilityM != null ? (data.visibilityM / 1000).toFixed(1) : '—'} <span className="text-[10px] text-white/40">km</span>
          </p>
          {data.visibilityM != null && (
            <p className="text-[10px] text-white/40">
              {data.visibilityM >= 10000 ? 'Clear' : data.visibilityM >= 5000 ? 'Good' : data.visibilityM >= 1000 ? 'Moderate' : 'Poor'}
            </p>
          )}
        </Tile>

        {/* Air Quality */}
        <Tile icon={<Activity size={15} />} label="Air Quality" accent="text-emerald-400">
          {aqiMeta ? (
            <>
              <p className={`text-lg font-semibold ${aqiMeta.text}`}>{Math.round(data.usAqi!)}</p>
              <p className={`text-[10px] ${aqiMeta.text}`}>{aqiMeta.label}</p>
              <Bar value={data.usAqi!} max={300} gradient={aqiMeta.color} />
            </>
          ) : (
            <p className="text-lg font-semibold text-white/30 italic text-sm">No data</p>
          )}
        </Tile>
      </div>

      {/* ── CAPE + Solar Cycle ── */}
      <div className="relative z-10 grid grid-cols-1 sm:grid-cols-2 gap-3">

        {/* CAPE */}
        {data.capeJkg != null && (() => {
          const cape = data.capeJkg;
          const riskLabel = cape < 500 ? 'Low' : cape < 1000 ? 'Moderate' : cape < 2000 ? 'High' : 'Extreme';
          const riskColor = cape < 500 ? 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20' :
                            cape < 1000 ? 'text-yellow-400 bg-yellow-500/10 border-yellow-500/20' :
                            cape < 2000 ? 'text-orange-400 bg-orange-500/10 border-orange-500/20' :
                            'text-rose-400 bg-rose-500/10 border-rose-500/20';
          return (
            <div className="flex items-center justify-between p-4 bg-black/20 rounded-2xl border border-white/10 backdrop-blur-lg">
              <div className="flex items-center gap-3">
                <div className={`p-2.5 rounded-xl border ${riskColor}`}>
                  <Zap size={18} />
                </div>
                <div>
                  <p className="text-[10px] font-bold text-white/40 uppercase tracking-widest">CAPE</p>
                  <p className="text-base font-bold text-white/90">{Math.round(cape)} <span className="text-[10px] font-normal text-white/40">J/kg</span></p>
                </div>
              </div>
              <span className={`px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-widest border ${riskColor}`}>{riskLabel} Risk</span>
            </div>
          );
        })()}

        {/* Solar Cycle */}
        {(data.sunrise || data.sunset) && (
          <div className="flex items-center justify-around p-4 bg-black/20 rounded-2xl border border-white/10 backdrop-blur-lg gap-3">
            <div className="flex items-center gap-2">
              <SunriseIcon size={16} className="text-amber-400" />
              <div>
                <p className="text-[9px] text-white/40 uppercase tracking-widest font-bold">Sunrise</p>
                <p className="text-sm font-bold text-white/90">{formatSolarTime(data.sunrise)}</p>
              </div>
            </div>
            <div className="w-px h-8 bg-white/10" />
            <div className="flex items-center gap-2">
              <SunsetIcon size={16} className="text-orange-400" />
              <div>
                <p className="text-[9px] text-white/40 uppercase tracking-widest font-bold">Sunset</p>
                <p className="text-sm font-bold text-white/90">{formatSolarTime(data.sunset)}</p>
              </div>
            </div>
            {data.isDay != null && (
              <>
                <div className="w-px h-8 bg-white/10" />
                <span className={`text-[9px] font-black uppercase tracking-widest px-2 py-1 rounded-full border ${
                  data.isDay ? 'bg-amber-500/20 text-amber-400 border-amber-500/30' : 'bg-indigo-500/20 text-indigo-300 border-indigo-500/30'
                }`}>
                  {data.isDay ? '☀ Day' : '☽ Night'}
                </span>
              </>
            )}
          </div>
        )}
      </div>

      {/* PM particles footnote if AQI available */}
      {(data.pm10 != null || data.pm25 != null) && (
        <div className="relative z-10 mt-3 flex gap-4 text-[10px] text-white/30 font-mono">
          {data.pm25 != null && <span>PM₂.₅ {data.pm25.toFixed(1)} µg/m³</span>}
          {data.pm10 != null && <span>PM₁₀ {data.pm10.toFixed(1)} µg/m³</span>}
        </div>
      )}
    </div>
  );
};
