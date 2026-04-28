import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  ComposedChart,
  Legend,
  AreaChart,
  Area,
  ReferenceLine,
} from 'recharts';
import {
  TrendingUp,
  CloudRain,
  AlertTriangle,
  Calendar,
  History,
  Info,
  Thermometer,
  Loader2,
  ShieldAlert,
  Radar,
  Gauge,
  Sparkles,
  CheckCircle2,
  XCircle,
  Satellite,
  Radio,
  Wind,
  Clock,
  Droplets,
  MapPin,
  Activity,
  Navigation,
} from 'lucide-react';
import { analyticsApi, weatherApi } from '../../api/endpoints';
import { useWeather } from '../../hooks/useWeather';
import { SpatialUnitSearch } from '../../components/common/SpatialUnitSearch';
import Card from '../../components/common/Card';
import { Badge } from '../../components/common/Badge';
import { StatCard } from '../../components/common/StatCard';
import { useLocationContextStore } from '../../store/locationContextStore';
import { getConditionText, getWeatherIcon, mapSymbolToWmo } from '../../components/weather/WeatherCard';
import type { WeatherResponse } from '../../components/weather/WeatherCard';

type AnalyticsTab = 'overview' | 'forecast' | 'patterns' | 'rainfall' | 'sources';

interface LocationOption {
  id: string;
  name: string;
  type: string;
  pcode?: string;
  lat?: number;
  lng?: number;
}

interface DailyWeatherDto {
  date: string;
  tempMean: number | null;
  precipMm: number | null;
  humidityMean: number | null;
}

interface ForecastDto {
  date: string;
  predictedPrecip: number | null;
  lowerBound: number | null;
  upperBound: number | null;
  qualityScore: number | null;
}

interface AnomalyDto {
  metric: string;
  month: number;
  classification: string;
  zScore: number | null;
}

interface MonthlyStatsDto {
  month: number;
  avgTemp: number | null;
  avgPrecip: number | null;
  avgHumidity: number | null;
}

interface WarningHistoryDto {
  totalWarnings: number;
  floodWarnings: number;
  landslideWarnings: number;
  lastWarningAt: string | null;
}

interface AnalyticsOverviewResponse {
  spatialUnitId: string;
  spatialUnitName: string;
  type: string;
  historicalTrend: DailyWeatherDto[];
  forecast: ForecastDto[];
  anomalies: AnomalyDto[];
  monthlyAverages: MonthlyStatsDto[];
  warningHistory: WarningHistoryDto;
}

interface ForecastAccuracyDto {
  spatialUnitId: string;
  totalForecasts: number;
  mae: number;
  hitRate: number;
}

interface ForecastHistoryPointDto {
  targetDate: string;
  predictedValue: number | null;
  actualValue: number | null;
  absoluteError: number | null;
  confidenceHit: boolean | null;
}

interface SatelliteRainfallDto {
  date: string;
  satelliteRainMm: number | null;
  stationRainMm: number | null;
  modelRainMm: number | null;
  discrepancyPercent: number | null;
  primarySource: string;
}

interface StationComparisonDto {
  stationId: string;
  stationName: string;
  distanceKm: number | null;
  stationTempC: number | null;
  stationHumidityPct: number | null;
  stationRainfallMm: number | null;
  interpolatedTempC: number | null;
  interpolatedHumidityPct: number | null;
  interpolatedRainfallMm: number | null;
  tempBiasC: number | null;
  dataQuality: string;
  stationAgeMinutes: number | null;
}

interface HourlyTrendDto {
  timestamp: string;
  temperatureC: number | null;
  precipitationMm: number | null;
}

interface ForecastShortIntervalDto {
  start: string;
  end: string;
  symbol?: { code?: string };
  symbolCode?: { next1Hour?: string };
  temperature?: { value?: number };
  feelsLike?: { value?: number };
  precipitation?: { value?: number };
  wind?: { speed?: number; direction?: number };
  cloudCover?: { value?: number };
  humidity?: { value?: number };
  dewPoint?: { value?: number };
  pressure?: { value?: number };
  uvIndex?: { value?: number };
}

interface ForecastDayIntervalDto {
  start: string;
  end: string;
  twentyFourHourSymbol?: string;
  twelveHourSymbols?: string[];
  sixHourSymbols?: string[];
  symbolConfidence?: string;
  precipitation?: { value?: number };
  temperature?: { min?: number; max?: number; value?: number };
  wind?: { min?: number; max?: number; direction?: number };
  uvIndex?: { max?: number };
}

interface AdvancedForecastResponse {
  tempC: number | null;
  apparentTempC: number | null;
  humidityPct: number | null;
  pressureHpa: number | null;
  windSpeedKmh: number | null;
  uvIndex: number | null;
  weatherCode?: number;
  isDay?: number;
  sunrise?: string | null;
  sunset?: string | null;
  dataQuality?: string | null;
  dayIntervals?: ForecastDayIntervalDto[];
  shortIntervals?: ForecastShortIntervalDto[];
}

const CHART_COLORS = {
  temp: '#38BDF8',
  precip: '#34D399',
  forecast: '#A78BFA',
  upper: '#F59E0B',
  lower: '#3B82F6',
};

function formatDay(dateString: string): string {
  const parsed = new Date(dateString);
  if (Number.isNaN(parsed.getTime())) {
    return dateString;
  }
  return parsed.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function formatDayLong(dateString: string): string {
  const parsed = new Date(dateString);
  if (Number.isNaN(parsed.getTime())) {
    return dateString;
  }
  return parsed.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
}

function formatTimeLabel(isoString: string): string {
  const parsed = new Date(isoString);
  if (Number.isNaN(parsed.getTime())) {
    return isoString;
  }

  return parsed.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function formatShortHourLabel(isoString: string): string {
  const parsed = new Date(isoString);
  if (Number.isNaN(parsed.getTime())) {
    return isoString;
  }

  return parsed.toLocaleTimeString(undefined, { hour: '2-digit', hour12: false });
}


function windDirectionLabel(degrees?: number): string {
  if (degrees == null || Number.isNaN(degrees)) {
    return 'calm';
  }

  const directions = ['north', 'north east', 'east', 'south east', 'south', 'south west', 'west', 'north west'];
  return directions[Math.round(degrees / 45) % 8];
}

export default function AnalyticsPage() {
  const { selectedLocation, setSelectedLocation } = useLocationContextStore();
  const [selectedUnit, setSelectedUnit] = useState<LocationOption | null>(null);
  const [activeTab, setActiveTab] = useState<AnalyticsTab>('overview');
  const [showBriefing, setShowBriefing] = useState(true);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [isLocating, setIsLocating] = useState(false);
  const [locationMessage, setLocationMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedLocation) {
      if (selectedUnit) {
        setSelectedUnit(null);
      }
      return;
    }

    if (!selectedUnit || selectedUnit.id !== selectedLocation.id) {
      setSelectedUnit({
        id: selectedLocation.id,
        name: selectedLocation.name,
        type: selectedLocation.type,
        pcode: selectedLocation.pcode,
        lat: selectedLocation.lat,
        lng: selectedLocation.lng,
      });
    }
  }, [selectedLocation, selectedUnit]);

  const { data: analytics, isLoading } = useQuery<AnalyticsOverviewResponse>({
    queryKey: ['analyticsOverview', selectedUnit?.id],
    queryFn: () => analyticsApi.getOverview(selectedUnit!.id),
    enabled: !!selectedUnit?.id,
  });

  const { data: accuracy } = useQuery<ForecastAccuracyDto>({
    queryKey: ['analyticsAccuracy', selectedUnit?.id],
    queryFn: () => analyticsApi.getForecastAccuracy(selectedUnit!.id, { days: 30, metric: 'all' }),
    enabled: !!selectedUnit?.id,
  });

  const { data: forecastHistory } = useQuery<ForecastHistoryPointDto[]>({
    queryKey: ['analyticsForecastHistory', selectedUnit?.id],
    queryFn: () => analyticsApi.getForecastHistory(selectedUnit!.id, 'precipitation', { days: 30 }),
    enabled: !!selectedUnit?.id,
  });

  const { data: satelliteRain, isLoading: rainLoading } = useQuery<SatelliteRainfallDto[]>({
    queryKey: ['analyticsSatelliteRain', selectedUnit?.id],
    queryFn: () => analyticsApi.getSatelliteRainfall(selectedUnit!.id, { days: 7 }),
    enabled: !!selectedUnit?.id,
  });

  const { data: stationComparison, isLoading: stationLoading } = useQuery<StationComparisonDto[]>({
    queryKey: ['analyticsStationComparison', selectedUnit?.id],
    queryFn: () => analyticsApi.getStationComparison(selectedUnit!.id),
    enabled: !!selectedUnit?.id,
  });

  const { data: hourlyTrend, isLoading: hourlyLoading } = useQuery<HourlyTrendDto[]>({
    queryKey: ['analyticsHourlyTrend', selectedUnit?.id],
    queryFn: () => analyticsApi.getHourlyTrend(selectedUnit!.id, { hours: 72, metric: 'all' }),
    enabled: !!selectedUnit?.id,
  });

  const { data: weather, isLoading: weatherLoading } = useWeather(selectedUnit?.id) as {
    data: AdvancedForecastResponse | undefined;
    isLoading: boolean;
  };

  const historicalTrend = analytics?.historicalTrend ?? [];
  const forecast = analytics?.forecast ?? [];
  const anomalies = analytics?.anomalies ?? [];
  const monthlyAverages = analytics?.monthlyAverages ?? [];

  const trendSummary = useMemo(() => {
    if (historicalTrend.length < 2) {
      return { deltaTemp: 0, deltaRain: 0 };
    }

    const first = historicalTrend[0];
    const last = historicalTrend[historicalTrend.length - 1];

    return {
      deltaTemp: (last.tempMean ?? 0) - (first.tempMean ?? 0),
      deltaRain: (last.precipMm ?? 0) - (first.precipMm ?? 0),
    };
  }, [historicalTrend]);

  const recentAverages = useMemo(() => {
    const recentDays = historicalTrend.slice(-7);
    const recentAvgTemp = recentDays.length
      ? recentDays.reduce((sum, day) => sum + (day.tempMean ?? 0), 0) / recentDays.length
      : 0;
    const recentAvgHumidity = recentDays.length
      ? recentDays.reduce((sum, day) => sum + (day.humidityMean ?? 0), 0) / recentDays.length
      : 0;
    const forecastRainTotal = forecast.reduce((sum, day) => sum + (day.predictedPrecip ?? 0), 0);

    return {
      recentAvgTemp,
      recentAvgHumidity,
      forecastRainTotal,
    };
  }, [historicalTrend, forecast]);

  const reliabilitySummary = useMemo(() => {
    const totalForecasts = accuracy?.totalForecasts ?? 0;
    const mae = accuracy?.mae ?? 0;
    const hitRate = accuracy?.hitRate ?? 0;
    const boundedForecasts = forecast.filter((f) => f.upperBound != null && f.lowerBound != null);
    const avgBandWidth = boundedForecasts.length
      ? boundedForecasts.reduce((sum, f) => sum + ((f.upperBound ?? 0) - (f.lowerBound ?? 0)), 0) / boundedForecasts.length
      : 0;

    return { totalForecasts, mae, hitRate, avgBandWidth };
  }, [accuracy, forecast]);

  const simpleBriefing = useMemo(() => {
    if (!analytics) {
      return '';
    }

    const rainDays = forecast.filter((f) => (f.predictedPrecip ?? 0) >= 5).length;
    const tempDirection = trendSummary.deltaTemp > 0.5 ? 'warming' : trendSummary.deltaTemp < -0.5 ? 'cooling' : 'stable';
    const confidenceLevel =
      reliabilitySummary.hitRate >= 75
        ? 'high'
        : reliabilitySummary.hitRate >= 55
          ? 'moderate'
          : reliabilitySummary.totalForecasts === 0
            ? 'insufficient'
            : 'low';

    const lines = [
      `Temperature is currently ${tempDirection} over the recent period.`,
      rainDays > 0
        ? `Forecast suggests ${rainDays} wetter day${rainDays > 1 ? 's' : ''} in the next two weeks.`
        : 'Forecast suggests mostly low-rainfall conditions for the next two weeks.',
      anomalies.length > 0
        ? `There ${anomalies.length === 1 ? 'is' : 'are'} ${anomalies.length} active anomaly signal${anomalies.length > 1 ? 's' : ''} to monitor.`
        : 'No strong anomaly signals detected right now.',
      confidenceLevel === 'high'
        ? 'Forecast confidence is currently strong based on recent model-vs-observation checks.'
        : confidenceLevel === 'moderate'
          ? 'Forecast confidence is moderate; monitor confidence bands for short-term changes.'
          : confidenceLevel === 'low'
            ? 'Forecast confidence is currently low, so treat medium-range projections conservatively.'
            : 'Forecast confidence cannot be graded yet because validated historical checks are limited.',
      `Warning history includes ${analytics.warningHistory?.totalWarnings ?? 0} total warnings for this area chain.`,
    ];

    return lines.join(' ');
  }, [analytics, forecast, anomalies.length, trendSummary.deltaTemp, reliabilitySummary.hitRate, reliabilitySummary.totalForecasts]);

  const handleSelectUnit = (unit: LocationOption) => {
    setLocationMessage(null);
    setSelectedUnit(unit);
    setSelectedLocation({
      id: unit.id,
      name: unit.name,
      type: unit.type,
      pcode: unit.pcode,
      lat: unit.lat,
      lng: unit.lng,
    });
  };

  const handleLocateMe = async () => {
    if (!navigator.geolocation) {
      return;
    }

    setIsLocating(true);

    try {
      const position = await new Promise<GeolocationPosition>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 60000,
        });
      });

      const nearest = await weatherApi.getNearestWeather(position.coords.latitude, position.coords.longitude);
      const resolvedUnit = {
        id: nearest.spatialUnitId,
        name: nearest.spatialUnitName,
        type: nearest.spatialUnitType,
        lat: position.coords.latitude,
        lng: position.coords.longitude,
      };

      setSelectedUnit(resolvedUnit);
      setSelectedLocation({
        id: resolvedUnit.id,
        name: resolvedUnit.name,
        type: resolvedUnit.type,
      });
      setLocationMessage(`Located nearest unit for your current position: ${nearest.spatialUnitName}.`);
    } catch {
      setLocationMessage('Could not determine your location. Please search for a spatial unit instead.');
    } finally {
      setIsLocating(false);
    }
  };

  const weatherCardData = weather as WeatherResponse | undefined;
  const hourlyForecastRows = weather?.shortIntervals ?? [];
  const dailyForecastRows = weather?.dayIntervals ?? [];

  const meteogramData = useMemo(() => {
    let lastDay = '';
    return hourlyForecastRows.slice(0, 48).map(hour => {
      const d = new Date(hour.start);
      const dayName = d.toLocaleDateString(undefined, { weekday: 'short' });
      const isDayBoundary = dayName !== lastDay;
      lastDay = dayName;

      return {
        time: formatTimeLabel(hour.start),
        displayTime: isDayBoundary ? `${dayName} ${formatTimeLabel(hour.start)}` : formatTimeLabel(hour.start),
        dayName,
        isDayBoundary,
        fullDate: hour.start,
        temp: hour.temperature?.value ?? null,
        feelsLike: hour.feelsLike?.value ?? null,
        precip: hour.precipitation?.value ?? 0,
        windSpeed: hour.wind?.speed ?? null,
        humidity: hour.humidity?.value ?? null,
        pressure: hour.pressure?.value ?? null,
        uvIndex: hour.uvIndex?.value ?? 0
      };
    });
  }, [hourlyForecastRows]);

  return (
    <div className="bg-slate-900 text-slate-100 min-h-[calc(100vh-80px)] rounded-xl font-sans space-y-8 p-6">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-6 pb-4 border-b border-slate-700">
        <div>
          <h1 className="text-3xl font-black text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-emerald-400">
            Climate Analytics
          </h1>
          <p className="text-slate-400 mt-1 font-medium">Easy-to-read trends, forecast confidence, and anomaly insights.</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => setShowBriefing((value) => !value)}
            className="bg-slate-800 hover:bg-slate-700 text-slate-200 px-4 py-2 flex items-center gap-2 rounded-xl font-semibold transition border border-slate-600"
          >
            <Sparkles className="w-4 h-4" /> {showBriefing ? 'Hide Summary' : 'Show Summary'}
          </button>
          <button
            onClick={() => setShowAdvanced((value) => !value)}
            className="bg-slate-800 hover:bg-slate-700 text-slate-200 px-4 py-2 flex items-center gap-2 rounded-xl font-semibold transition border border-slate-600"
          >
            <Gauge className="w-4 h-4" /> {showAdvanced ? 'Basic View' : 'Advanced View'}
          </button>
        </div>
      </div>

      <Card className="p-5 space-y-4">
        <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4">
          <div className="space-y-2">
            <p className="text-sm text-slate-400 mb-2">Choose a spatial unit</p>
            <div className="flex flex-col sm:flex-row gap-3 max-w-2xl">
              <SpatialUnitSearch onSelect={handleSelectUnit} className="flex-1 max-w-none" />
              <button
                type="button"
                onClick={() => void handleLocateMe()}
                disabled={isLocating}
                className="sm:w-12 w-full shrink-0 bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-600 rounded-xl font-semibold transition flex items-center justify-center min-h-[44px] disabled:opacity-60"
                title="Locate me"
              >
                <Navigation className={isLocating ? 'w-4 h-4 animate-pulse' : 'w-4 h-4'} />
                <span className="sm:hidden ml-2">Locate me</span>
              </button>
            </div>
            {locationMessage && <p className="text-xs text-slate-500">{locationMessage}</p>}
          </div>

          {selectedUnit && (
            <div className="bg-slate-900/50 p-4 rounded-xl border border-slate-700 min-w-[240px]">
              <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">Selected</p>
              <p className="text-lg font-bold text-white mt-1">{selectedUnit.name}</p>
              <p className="text-xs text-slate-400 mt-1 flex items-center gap-1">
                <Info className="w-3 h-3" /> {selectedUnit.type} {selectedUnit.pcode ? `• ${selectedUnit.pcode}` : ''}
              </p>
            </div>
          )}
        </div>

        {weatherCardData ? (
          <div className="grid grid-cols-2 lg:grid-cols-6 gap-3">
            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4 flex items-center gap-3 lg:col-span-2">
              <div className="text-blue-300">{getWeatherIcon(weatherCardData.weatherCode ?? 3, weatherCardData.isDay ?? 1, 'w-12 h-12')}</div>
              <div>
                <p className="text-[10px] uppercase tracking-widest text-slate-500">Condition</p>
                <p className="text-sm font-semibold text-slate-100">{getConditionText(weatherCardData.weatherCode ?? 3)}</p>
                <p className="text-[10px] text-slate-500 mt-1">{weatherCardData.dataQuality ?? '—'}</p>
              </div>
            </div>

            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4">
              <p className="text-[10px] uppercase tracking-widest text-slate-500">Temperature</p>
              <p className="text-2xl font-black text-blue-300 mt-1">
                {weatherCardData.tempC != null ? `${weatherCardData.tempC.toFixed(1)}°` : '—'}
              </p>
              <p className="text-xs text-slate-500 mt-1">
                Feels {weatherCardData.apparentTempC != null ? `${weatherCardData.apparentTempC.toFixed(1)}°` : '—'}
              </p>
            </div>

            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4">
              <p className="text-[10px] uppercase tracking-widest text-slate-500">Wind</p>
              <p className="text-2xl font-black text-amber-300 mt-1">
                {weatherCardData.windSpeedKmh != null ? `${weatherCardData.windSpeedKmh.toFixed(1)}` : '—'}
              </p>
              <p className="text-xs text-slate-500 mt-1">km/h</p>
            </div>

            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4">
              <p className="text-[10px] uppercase tracking-widest text-slate-500">Rain</p>
              <p className="text-2xl font-black text-emerald-300 mt-1">
                {weatherCardData.precipitationMm != null ? `${weatherCardData.precipitationMm.toFixed(1)}` : '—'}
              </p>
              <p className="text-xs text-slate-500 mt-1">mm</p>
            </div>

            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4">
              <p className="text-[10px] uppercase tracking-widest text-slate-500">Humidity</p>
              <p className="text-2xl font-black text-purple-300 mt-1">
                {weatherCardData.humidityPct != null ? `${weatherCardData.humidityPct.toFixed(0)}%` : '—'}
              </p>
              <p className="text-xs text-slate-500 mt-1">
                Pressure {weatherCardData.pressureHpa != null ? `${weatherCardData.pressureHpa.toFixed(0)} hPa` : '—'}
              </p>
            </div>

            <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-4">
              <p className="text-[10px] uppercase tracking-widest text-slate-500">Sun / UV</p>
              <p className="text-lg font-black text-amber-300 mt-1">
                {weatherCardData.sunrise && weatherCardData.sunset ? 'Daylight' : '—'}
              </p>
              <p className="text-xs text-slate-500 mt-1">
                UV {weatherCardData.uvIndex != null ? weatherCardData.uvIndex.toFixed(1) : '—'}
              </p>
            </div>
          </div>
        ) : (
          <div className="h-24 rounded-2xl border border-dashed border-slate-700 flex items-center justify-center text-sm text-slate-500">
            Select a unit or use Locate me to load current conditions.
          </div>
        )}
      </Card>

      {!selectedUnit ? (
        <div className="py-16 text-center space-y-4">
          <div className="bg-slate-800 w-20 h-20 rounded-full flex items-center justify-center mx-auto border border-slate-700">
            <Radar className="w-8 h-8 text-slate-500" />
          </div>
          <h3 className="text-xl font-bold text-white">Select a location to begin</h3>
          <p className="text-slate-400">Search District, DS, or GN units and this page will load analytics and weather context.</p>
        </div>
      ) : isLoading ? (
        <div className="py-20 flex flex-col items-center justify-center gap-4">
          <Loader2 className="w-12 h-12 text-emerald-500 animate-spin" />
          <p className="text-slate-400 font-medium">Loading analytics overview...</p>
        </div>
      ) : analytics ? (
        <div className="space-y-8 animate-in fade-in duration-700">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <Card className="p-4">
              <p className="text-xs text-slate-400 mb-1">Current Temp</p>
              <p className="text-2xl font-bold text-blue-300">{weather?.tempC != null ? `${weather.tempC.toFixed(1)}°C` : 'N/A'}</p>
              <p className="text-xs text-slate-500 mt-1">Feels like {weather?.apparentTempC != null ? `${weather.apparentTempC.toFixed(1)}°C` : 'N/A'}</p>
            </Card>
            <Card className="p-4">
              <p className="text-xs text-slate-400 mb-1">Humidity / Pressure</p>
              <p className="text-2xl font-bold text-emerald-300">{weather?.humidityPct != null ? `${weather.humidityPct.toFixed(0)}%` : 'N/A'}</p>
              <p className="text-xs text-slate-500 mt-1">{weather?.pressureHpa != null ? `${weather.pressureHpa.toFixed(0)} hPa` : 'N/A'}</p>
            </Card>
            <Card className="p-4">
              <p className="text-xs text-slate-400 mb-1">Wind / UV</p>
              <p className="text-2xl font-bold text-amber-300">{weather?.windSpeedKmh != null ? `${weather.windSpeedKmh.toFixed(1)} km/h` : 'N/A'}</p>
              <p className="text-xs text-slate-500 mt-1">UV {weather?.uvIndex != null ? weather.uvIndex.toFixed(1) : 'N/A'}</p>
            </Card>
            <Card className="p-4">
              <p className="text-xs text-slate-400 mb-1">Hourly Points</p>
              <p className="text-2xl font-bold text-purple-300">{weather?.shortIntervals?.length ?? 0}</p>
              <p className="text-xs text-slate-500 mt-1">{weatherLoading ? 'Loading weather feed...' : 'From advanced weather intervals'}</p>
            </Card>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <StatCard
              title="Recent Avg Temp"
              value={recentAverages.recentAvgTemp.toFixed(1)}
              unit="°C"
              icon={Thermometer}
              color="text-blue-400 bg-blue-500/10 border-blue-500/20"
              trend={trendSummary.deltaTemp > 0 ? 'warming' : trendSummary.deltaTemp < 0 ? 'cooling' : 'stable'}
            />
            <StatCard
              title="14-Day Rain Total"
              value={recentAverages.forecastRainTotal.toFixed(1)}
              unit="mm"
              icon={CloudRain}
              color="text-emerald-400 bg-emerald-500/10 border-emerald-500/20"
            />
            <StatCard
              title="Anomaly Signals"
              value={anomalies.length}
              icon={ShieldAlert}
              color="text-amber-400 bg-amber-500/10 border-amber-500/20"
            />
            <StatCard
              title="Forecast Hit Rate"
              value={reliabilitySummary.hitRate.toFixed(1)}
              unit="%"
              icon={Gauge}
              color="text-purple-400 bg-purple-500/10 border-purple-500/20"
            />
          </div>

          {showBriefing && (
            <Card className="p-5 border-slate-600">
              <h3 className="text-lg font-bold flex items-center gap-2 mb-3 text-emerald-400">
                <Sparkles className="w-5 h-5" /> Plain-Language Summary
              </h3>
              <p className="text-slate-200 leading-relaxed">{simpleBriefing}</p>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mt-4">
                <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-3">
                  <p className="text-[11px] text-slate-500 uppercase tracking-wide">Mean Absolute Error</p>
                  <p className="text-lg font-bold text-slate-100">{reliabilitySummary.mae.toFixed(2)}</p>
                </div>
                <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-3">
                  <p className="text-[11px] text-slate-500 uppercase tracking-wide">Avg Forecast Band</p>
                  <p className="text-lg font-bold text-slate-100">{reliabilitySummary.avgBandWidth.toFixed(2)} mm</p>
                </div>
                <div className="bg-slate-900/40 border border-slate-700 rounded-xl p-3">
                  <p className="text-[11px] text-slate-500 uppercase tracking-wide">Recent Avg Humidity</p>
                  <p className="text-lg font-bold text-slate-100">{recentAverages.recentAvgHumidity.toFixed(0)}%</p>
                </div>
              </div>
              <p className="text-xs text-slate-500 mt-3">Generated from backend weather sync, node timeseries, station observations, JAXA rainfall grids, and forecast projection records.</p>
            </Card>
          )}

          <div className="bg-slate-800/30 p-1.5 rounded-xl border border-slate-700/50 flex flex-wrap gap-2 w-max max-w-full overflow-x-auto">
            {[
              { id: 'overview', label: 'Overview', icon: Activity },
              { id: 'forecast', label: 'Forecast', icon: Radar },
              { id: 'patterns', label: 'Patterns', icon: Calendar },
              { id: 'rainfall', label: 'Rainfall Analysis', icon: Satellite },
              { id: 'sources', label: 'Data Sources', icon: Radio },
            ].map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as AnalyticsTab)}
                className={`px-4 py-2 rounded-xl text-sm font-medium transition flex items-center gap-2 ${activeTab === tab.id ? 'bg-slate-700 text-white' : 'text-slate-400 hover:bg-slate-700/50 hover:text-slate-200'}`}
              >
                <tab.icon className="w-4 h-4" /> {tab.label}
              </button>
            ))}
          </div>

          {activeTab === 'overview' && (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <Card className="p-5">
                <h3 className="text-lg font-bold text-blue-300 flex items-center gap-2 mb-4">
                  <TrendingUp className="w-5 h-5" /> 30-Day Temperature
                </h3>
                <div className="h-[280px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={historicalTrend}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                      <XAxis dataKey="date" stroke="#94A3B8" fontSize={11} tickFormatter={formatDay} />
                      <YAxis stroke="#94A3B8" fontSize={11} unit="°C" />
                      <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                      <Line type="monotone" dataKey="tempMean" stroke={CHART_COLORS.temp} strokeWidth={2.5} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </Card>

              <Card className="p-5">
                <h3 className="text-lg font-bold text-emerald-400 flex items-center gap-2 mb-4">
                  <CloudRain className="w-5 h-5" /> 30-Day Rainfall
                </h3>
                <div className="h-[280px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={historicalTrend}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                      <XAxis dataKey="date" stroke="#94A3B8" fontSize={11} tickFormatter={formatDay} />
                      <YAxis stroke="#94A3B8" fontSize={11} unit="mm" />
                      <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                      <Bar dataKey="precipMm" fill={CHART_COLORS.precip} radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </Card>
            </div>
          )}

          {activeTab === 'forecast' && (
            <div className="space-y-6">
              {weatherCardData && (
                <Card className="p-5">
                  <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
                    <div className="flex items-center gap-4">
                      <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-slate-900/60 border border-slate-700 text-blue-300">
                        {getWeatherIcon(weatherCardData.weatherCode ?? 3, weatherCardData.isDay ?? 1, 'w-10 h-10')}
                      </div>
                      <div>
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Current conditions</p>
                        <h3 className="text-xl font-bold text-slate-100">{getConditionText(weatherCardData.weatherCode ?? 3)}</h3>
                        <p className="text-sm text-slate-400 mt-1">{weatherCardData.dataQuality ?? 'Backend weather feed'}</p>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3 w-full lg:w-auto">
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Temp</p>
                        <p className="text-lg font-black text-blue-300">{weatherCardData.tempC != null ? `${weatherCardData.tempC.toFixed(1)}°` : '—'}</p>
                      </div>
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Feels</p>
                        <p className="text-lg font-black text-emerald-300">{weatherCardData.apparentTempC != null ? `${weatherCardData.apparentTempC.toFixed(1)}°` : '—'}</p>
                      </div>
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Wind</p>
                        <p className="text-lg font-black text-amber-300">{weatherCardData.windSpeedKmh != null ? `${weatherCardData.windSpeedKmh.toFixed(1)} km/h` : '—'}</p>
                      </div>
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Rain</p>
                        <p className="text-lg font-black text-cyan-300">{weatherCardData.precipitationMm != null ? `${weatherCardData.precipitationMm.toFixed(1)} mm` : '—'}</p>
                      </div>
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">Humidity</p>
                        <p className="text-lg font-black text-purple-300">{weatherCardData.humidityPct != null ? `${weatherCardData.humidityPct.toFixed(0)}%` : '—'}</p>
                      </div>
                      <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-[10px] uppercase tracking-widest text-slate-500">UV</p>
                        <p className="text-lg font-black text-amber-300">{weatherCardData.uvIndex != null ? weatherCardData.uvIndex.toFixed(1) : '—'}</p>
                      </div>
                    </div>
                  </div>
                </Card>
              )}

              <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
                <Card className="p-5 xl:col-span-2">
                  <h3 className="text-lg font-bold text-blue-300 flex items-center gap-2 mb-4">
                    <Calendar className="w-5 h-5" /> 14-Day forecast table
                  </h3>
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[900px] border-separate border-spacing-y-2">
                      <thead>
                        <tr className="text-left text-[10px] uppercase tracking-widest text-slate-500">
                          <th className="px-3 py-2">Date</th>
                          <th className="px-3 py-2">Night</th>
                          <th className="px-3 py-2">Morning</th>
                          <th className="px-3 py-2">Afternoon</th>
                          <th className="px-3 py-2">Evening</th>
                          <th className="px-3 py-2">Min / Max</th>
                          <th className="px-3 py-2">Rain</th>
                          <th className="px-3 py-2">Wind</th>
                        </tr>
                      </thead>
                      <tbody>
                        {dailyForecastRows.map((day, index) => (
                          <tr key={`${day.start}-${index}`} className="rounded-xl bg-slate-900/40 border border-slate-700">
                            <td className="px-3 py-3 rounded-l-xl">
                              <div className="font-semibold text-slate-100">{index === 0 ? 'Today' : formatDayLong(day.start)}</div>
                            </td>
                            {(day.sixHourSymbols ?? []).map((symbol, periodIndex) => (
                              <td key={`${symbol}-${periodIndex}`} className="px-3 py-3 align-top">
                                <div className="flex items-center gap-2">
                                  <span className="text-blue-300">{getWeatherIcon(mapSymbolToWmo(symbol), 1, 'w-5 h-5')}</span>
                                  <div>
                                    <p className="text-xs font-semibold text-slate-200 capitalize">
                                      {getConditionText(mapSymbolToWmo(symbol))}
                                    </p>
                                    <p className="text-[10px] text-slate-500">{['Night', 'Morning', 'Afternoon', 'Evening'][periodIndex]}</p>
                                  </div>
                                </div>
                              </td>
                            ))}
                            <td className="px-3 py-3 text-slate-100 font-semibold">
                              {day.temperature?.max != null ? `${day.temperature.max.toFixed(0)}°` : '—'} / {day.temperature?.min != null ? `${day.temperature.min.toFixed(0)}°` : '—'}
                            </td>
                            <td className="px-3 py-3 text-slate-100 font-semibold">
                              {day.precipitation?.value != null ? `${day.precipitation.value.toFixed(1)} mm` : '—'}
                            </td>
                            <td className="px-3 py-3 rounded-r-xl text-slate-100 font-semibold">
                              {day.wind?.max != null ? `${day.wind.max.toFixed(0)} km/h` : '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>

                <Card className="p-5 xl:col-span-2">
                  <h3 className="text-lg font-bold text-emerald-400 flex items-center gap-2 mb-4">
                    <Clock className="w-5 h-5" /> 24-hour forecast table
                  </h3>
                  <div className="overflow-x-auto max-h-[560px] overflow-y-auto">
                    <table className="w-full min-w-[900px] border-separate border-spacing-y-2">
                      <thead>
                        <tr className="text-left text-[10px] uppercase tracking-widest text-slate-500">
                          <th className="px-3 py-2">Time</th>
                          <th className="px-3 py-2">Weather</th>
                          <th className="px-3 py-2">Temp</th>
                          <th className="px-3 py-2">Feels</th>
                          <th className="px-3 py-2">Rain</th>
                          <th className="px-3 py-2">Wind</th>
                          <th className="px-3 py-2">Humidity</th>
                          <th className="px-3 py-2">Pressure</th>
                        </tr>
                      </thead>
                      <tbody>
                        {hourlyForecastRows.slice(0, 24).map((hour, index) => {
                          const symbol = hour.symbolCode?.next1Hour ?? hour.symbol?.code;
                          return (
                            <tr key={`${hour.start}-${index}`} className="rounded-xl bg-slate-900/40 border border-slate-700">
                              <td className="px-3 py-3 rounded-l-xl font-semibold text-slate-100">{index === 0 ? 'Now' : formatTimeLabel(hour.start)}</td>
                              <td className="px-3 py-3">
                                <div className="flex items-center gap-2">
                                  <span className="text-blue-300">{getWeatherIcon(mapSymbolToWmo(symbol), 1, 'w-5 h-5')}</span>
                                  <span className="text-sm text-slate-200 capitalize">{getConditionText(mapSymbolToWmo(symbol))}</span>
                                </div>
                              </td>
                              <td className="px-3 py-3 text-slate-100 font-semibold">{hour.temperature?.value != null ? `${hour.temperature.value.toFixed(1)}°` : '—'}</td>
                              <td className="px-3 py-3 text-slate-100 font-semibold">{hour.feelsLike?.value != null ? `${hour.feelsLike.value.toFixed(1)}°` : '—'}</td>
                              <td className="px-3 py-3 text-slate-100 font-semibold">{hour.precipitation?.value != null ? `${hour.precipitation.value.toFixed(1)} mm` : '—'}</td>
                              <td className="px-3 py-3 text-slate-100 font-semibold">{hour.wind?.speed != null ? `${hour.wind.speed.toFixed(1)} km/h ${windDirectionLabel(hour.wind.direction)}` : '—'}</td>
                              <td className="px-3 py-3 text-slate-100 font-semibold">{hour.humidity?.value != null ? `${hour.humidity.value.toFixed(0)}%` : '—'}</td>
                              <td className="px-3 py-3 rounded-r-xl text-slate-100 font-semibold">{hour.pressure?.value != null ? `${hour.pressure.value.toFixed(0)} hPa` : '—'}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </Card>

                <Card className="p-5 lg:col-span-2">
                  <h3 className="text-lg font-bold text-slate-200 flex items-center gap-2 mb-4 border-b border-slate-700 pb-2">
                    <Activity className="w-5 h-5 text-blue-400" /> Advanced Meteogram (48-hour)
                  </h3>
                  
                  <div className="space-y-8">
                    {/* Temperature */}
                    <div>
                      <p className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">Temperature & Feels Like (°C)</p>
                      <div className="h-[200px]">
                        <ResponsiveContainer width="100%" height="100%">
                          <AreaChart data={meteogramData}>
                            <defs>
                              <linearGradient id="tempGrad" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="5%" stopColor="#38BDF8" stopOpacity={0.3}/>
                                <stop offset="95%" stopColor="#38BDF8" stopOpacity={0}/>
                              </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                            <XAxis dataKey="time" stroke="#94A3B8" fontSize={10} minTickGap={20} tickFormatter={(val, i) => meteogramData[i]?.isDayBoundary ? `${meteogramData[i].dayName} ${val}` : val} />
                            <YAxis stroke="#94A3B8" fontSize={11} domain={['dataMin - 2', 'dataMax + 2']} />
                            <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                            <Legend />
                            {meteogramData.map((d, i) => d.isDayBoundary && i > 0 ? (
                              <ReferenceLine key={i} x={d.time} stroke="#475569" strokeDasharray="5 5" label={{ value: d.dayName, position: 'top', fill: '#94A3B8', fontSize: 10 }} />
                            ) : null)}
                            <Area type="monotone" dataKey="temp" name="Temperature" stroke="#38BDF8" fillOpacity={1} fill="url(#tempGrad)" strokeWidth={2} />
                            <Line type="monotone" dataKey="feelsLike" name="Feels Like" stroke="#F59E0B" strokeWidth={1.5} dot={false} strokeDasharray="4 4" />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                    {/* Precipitation */}
                    <div>
                      <p className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">Precipitation (mm)</p>
                      <div className="h-[140px]">
                        <ResponsiveContainer width="100%" height="100%">
                          <BarChart data={meteogramData}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                            <XAxis dataKey="time" stroke="#94A3B8" fontSize={10} minTickGap={20} tickFormatter={(val, i) => meteogramData[i]?.isDayBoundary ? `${meteogramData[i].dayName} ${val}` : val} />
                            <YAxis stroke="#94A3B8" fontSize={11} />
                            <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                            {meteogramData.map((d, i) => d.isDayBoundary && i > 0 ? (
                              <ReferenceLine key={i} x={d.time} stroke="#475569" strokeDasharray="5 5" />
                            ) : null)}
                            <Bar dataKey="precip" name="Rain" fill="#34D399" radius={[2, 2, 0, 0]} />
                          </BarChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                    {/* Wind */}
                    <div>
                      <p className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">Wind Speed (km/h)</p>
                      <div className="h-[140px]">
                        <ResponsiveContainer width="100%" height="100%">
                          <AreaChart data={meteogramData}>
                            <defs>
                              <linearGradient id="windGrad" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="5%" stopColor="#A78BFA" stopOpacity={0.3}/>
                                <stop offset="95%" stopColor="#A78BFA" stopOpacity={0}/>
                              </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                            <XAxis dataKey="time" stroke="#94A3B8" fontSize={10} minTickGap={20} tickFormatter={(val, i) => meteogramData[i]?.isDayBoundary ? `${meteogramData[i].dayName} ${val}` : val} />
                            <YAxis stroke="#94A3B8" fontSize={11} />
                            <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                            {meteogramData.map((d, i) => d.isDayBoundary && i > 0 ? (
                              <ReferenceLine key={i} x={d.time} stroke="#475569" strokeDasharray="5 5" />
                            ) : null)}
                            <Area type="monotone" dataKey="windSpeed" name="Wind Speed" stroke="#A78BFA" fillOpacity={1} fill="url(#windGrad)" strokeWidth={2} />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                    {/* Humidity & Pressure */}
                    <div>
                      <p className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">Humidity (%) & Pressure (hPa)</p>
                      <div className="h-[160px]">
                        <ResponsiveContainer width="100%" height="100%">
                          <ComposedChart data={meteogramData}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                            <XAxis dataKey="time" stroke="#94A3B8" fontSize={10} minTickGap={20} tickFormatter={(val, i) => meteogramData[i]?.isDayBoundary ? `${meteogramData[i].dayName} ${val}` : val} />
                            <YAxis yAxisId="left" stroke="#94A3B8" fontSize={11} domain={[0, 100]} />
                            <YAxis yAxisId="right" orientation="right" stroke="#94A3B8" fontSize={11} domain={['dataMin - 5', 'dataMax + 5']} />
                            <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                            <Legend />
                            {meteogramData.map((d, i) => d.isDayBoundary && i > 0 ? (
                              <ReferenceLine key={i} x={d.time} yAxisId="left" stroke="#475569" strokeDasharray="5 5" />
                            ) : null)}
                            <Line yAxisId="left" type="monotone" dataKey="humidity" name="Humidity" stroke="#818CF8" strokeWidth={2} dot={false} />
                            <Line yAxisId="right" type="monotone" dataKey="pressure" name="Pressure" stroke="#F472B6" strokeWidth={2} dot={false} />
                          </ComposedChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                  </div>
                </Card>
              </div>
            </div>
          )}

          {activeTab === 'patterns' && (
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
              <Card className="p-5 lg:col-span-2">
                <h3 className="text-lg font-bold text-blue-300 flex items-center gap-2 mb-4">
                  <Calendar className="w-5 h-5" /> Monthly Climate Averages
                </h3>
                <div className="h-[320px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={monthlyAverages}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                      <XAxis dataKey="month" stroke="#94A3B8" fontSize={11} />
                      <YAxis yAxisId="temp" stroke="#94A3B8" fontSize={11} unit="°C" />
                      <YAxis yAxisId="precip" orientation="right" stroke="#94A3B8" fontSize={11} unit="mm" />
                      <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                      <Legend />
                      <Line yAxisId="temp" type="monotone" dataKey="avgTemp" stroke={CHART_COLORS.temp} name="Avg Temp" strokeWidth={2} dot={false} />
                      <Bar yAxisId="precip" dataKey="avgPrecip" fill={CHART_COLORS.precip} name="Avg Rain" />
                    </ComposedChart>
                  </ResponsiveContainer>
                </div>
              </Card>

              <Card className="p-5">
                <h3 className="text-lg font-bold text-purple-400 flex items-center gap-2 mb-4">
                  <History className="w-5 h-5" /> Forecast Reliability
                </h3>
                <div className="space-y-3">
                  <div className="p-3 rounded-xl bg-slate-900/40 border border-slate-700">
                    <p className="text-xs text-slate-400">Forecasts Evaluated</p>
                    <p className="text-2xl font-bold text-white">{reliabilitySummary.totalForecasts}</p>
                  </div>
                  <div className="p-3 rounded-xl bg-slate-900/40 border border-slate-700">
                    <p className="text-xs text-slate-400">Mean Absolute Error</p>
                    <p className="text-2xl font-bold text-white">{reliabilitySummary.mae.toFixed(2)}</p>
                  </div>
                  <div className="p-3 rounded-xl bg-slate-900/40 border border-slate-700">
                    <p className="text-xs text-slate-400">Confidence Hit Rate</p>
                    <p className="text-2xl font-bold text-white">{reliabilitySummary.hitRate.toFixed(1)}%</p>
                  </div>
                </div>

                {showAdvanced && (
                  <div className="mt-4 max-h-[140px] overflow-auto space-y-2">
                    {(forecastHistory || []).slice(0, 6).map((entry, idx) => (
                      <div key={`${entry.targetDate}-${idx}`} className="p-2 rounded-xl border border-slate-700 bg-slate-900/40 text-xs">
                        <div className="flex justify-between">
                          <span className="text-slate-400">{entry.targetDate}</span>
                          {entry.confidenceHit === true && <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />}
                          {entry.confidenceHit === false && <XCircle className="w-3.5 h-3.5 text-rose-400" />}
                          {entry.confidenceHit == null && <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />}
                        </div>
                        <p className="text-slate-300 mt-1">Pred {entry.predictedValue != null ? entry.predictedValue.toFixed(2) : 'N/A'} • Actual {entry.actualValue != null ? entry.actualValue.toFixed(2) : 'N/A'}</p>
                      </div>
                    ))}
                  </div>
                )}
              </Card>
            </div>
          )}

          {activeTab === 'rainfall' && (
            <div className="space-y-6">
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <Card className="p-5">
                  <h3 className="text-lg font-bold text-cyan-400 flex items-center gap-2 mb-4">
                    <Satellite className="w-5 h-5" /> Satellite vs Station vs Model
                  </h3>
                  <div className="h-[320px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={satelliteRain || []}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                        <XAxis dataKey="date" stroke="#94A3B8" fontSize={11} tickFormatter={formatDay} />
                        <YAxis stroke="#94A3B8" fontSize={11} unit="mm" />
                        <Tooltip contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }} />
                        <Legend />
                        <Bar dataKey="satelliteRainMm" name="JAXA Satellite" fill="#06B6D4" radius={[4, 4, 0, 0]} />
                        <Bar dataKey="stationRainMm" name="Station (Ground Truth)" fill="#10B981" radius={[4, 4, 0, 0]} />
                        <Line type="monotone" dataKey="modelRainMm" name="Model Forecast" stroke="#A78BFA" strokeWidth={2} dot={false} />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                  {rainLoading && <p className="text-xs text-slate-500 mt-2 flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> Loading satellite data...</p>}
                </Card>

                <Card className="p-5">
                  <h3 className="text-lg font-bold text-amber-400 flex items-center gap-2 mb-4">
                    <Droplets className="w-5 h-5" /> Data Source Discrepancy
                  </h3>
                  <div className="space-y-3 max-h-[320px] overflow-auto">
                    {(satelliteRain || []).slice(0, 7).map((day, idx) => (
                      <div key={idx} className="p-3 rounded-xl border border-slate-700 bg-slate-900/40">
                        <div className="flex justify-between items-center mb-2">
                          <span className="text-sm font-medium text-slate-200">{formatDayLong(day.date)}</span>
                          <Badge variant={day.primarySource === 'STATION' ? 'success' : day.primarySource === 'SATELLITE' ? 'info' : 'neutral'} size="sm">
                            {day.primarySource}
                          </Badge>
                        </div>
                        <div className="grid grid-cols-3 gap-2 text-xs">
                          <div className="text-center">
                            <p className="text-slate-500">Station</p>
                            <p className="text-emerald-400 font-semibold">{day.stationRainMm != null ? `${day.stationRainMm.toFixed(1)} mm` : 'N/A'}</p>
                          </div>
                          <div className="text-center">
                            <p className="text-slate-500">Satellite</p>
                            <p className="text-cyan-400 font-semibold">{day.satelliteRainMm != null ? `${day.satelliteRainMm.toFixed(1)} mm` : 'N/A'}</p>
                          </div>
                          <div className="text-center">
                            <p className="text-slate-500">Model</p>
                            <p className="text-purple-400 font-semibold">{day.modelRainMm != null ? `${day.modelRainMm.toFixed(1)} mm` : 'N/A'}</p>
                          </div>
                        </div>
                        {day.discrepancyPercent !== null && (
                          <p className="text-xs text-slate-500 mt-2 text-center">Δ {day.discrepancyPercent > 0 ? '+' : ''}{day.discrepancyPercent.toFixed(0)}% (sat vs station)</p>
                        )}
                      </div>
                    ))}
                    {(!satelliteRain || satelliteRain.length === 0) && !rainLoading && (
                      <p className="text-sm text-slate-400 italic">No satellite rainfall data available for this location.</p>
                    )}
                  </div>
                </Card>
              </div>
            </div>
          )}

          {activeTab === 'sources' && (
            <div className="space-y-6">
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <Card className="p-5">
                  <h3 className="text-lg font-bold text-emerald-400 flex items-center gap-2 mb-4">
                    <Radio className="w-5 h-5" /> Ground Station Comparison
                  </h3>
                  <div className="space-y-3 max-h-[400px] overflow-auto">
                    {(stationComparison || []).map((station, idx) => (
                      <div key={idx} className="p-4 rounded-xl border border-slate-700 bg-slate-900/40">
                        <div className="flex justify-between items-start mb-3">
                          <div>
                            <p className="text-sm font-semibold text-slate-200">{station.stationName}</p>
                            <p className="text-xs text-slate-500 flex items-center gap-1">
                              <MapPin className="w-3 h-3" /> {station.distanceKm != null ? `${station.distanceKm.toFixed(1)} km away` : '? km away'}
                            </p>
                          </div>
                          <Badge variant={station.dataQuality === 'STATION_DIRECT' ? 'success' : 'warning'} size="sm">
                            {station.dataQuality}
                          </Badge>
                        </div>
                        <div className="grid grid-cols-3 gap-3 text-sm">
                          <div>
                            <p className="text-xs text-slate-500">Temperature</p>
                            <div className="flex items-center gap-2">
                              <span className="text-emerald-400">{station.stationTempC != null ? `${station.stationTempC.toFixed(1)}°C` : 'N/A'}</span>
                              <span className="text-slate-600">vs</span>
                              <span className="text-blue-400">{station.interpolatedTempC != null ? `${station.interpolatedTempC.toFixed(1)}°C` : 'N/A'}</span>
                            </div>
                            {station.tempBiasC != null && (
                              <p className={`text-xs ${Math.abs(station.tempBiasC) < 1 ? 'text-emerald-500' : Math.abs(station.tempBiasC) < 2 ? 'text-amber-500' : 'text-rose-500'}`}>
                                Δ {station.tempBiasC > 0 ? '+' : ''}{station.tempBiasC.toFixed(1)}°C
                              </p>
                            )}
                          </div>
                          <div>
                            <p className="text-xs text-slate-500">Humidity</p>
                            <div className="flex items-center gap-2">
                              <span className="text-emerald-400">{station.stationHumidityPct != null ? `${station.stationHumidityPct.toFixed(0)}%` : 'N/A'}</span>
                              <span className="text-slate-600">vs</span>
                              <span className="text-blue-400">{station.interpolatedHumidityPct != null ? `${station.interpolatedHumidityPct.toFixed(0)}%` : 'N/A'}</span>
                            </div>
                          </div>
                          <div>
                            <p className="text-xs text-slate-500">Rainfall</p>
                            <div className="flex items-center gap-2">
                              <span className="text-emerald-400">{station.stationRainfallMm != null ? `${station.stationRainfallMm.toFixed(1)}mm` : 'N/A'}</span>
                              <span className="text-slate-600">vs</span>
                              <span className="text-blue-400">{station.interpolatedRainfallMm != null ? `${station.interpolatedRainfallMm.toFixed(1)}mm` : 'N/A'}</span>
                            </div>
                          </div>
                        </div>
                        <p className="text-xs text-slate-600 mt-2">
                          Updated {station.stationAgeMinutes == null ? 'N/A' : station.stationAgeMinutes < 60 ? `${station.stationAgeMinutes} min ago` : `${Math.floor(station.stationAgeMinutes / 60)}h ago`}
                        </p>
                      </div>
                    ))}
                    {(!stationComparison || stationComparison.length === 0) && !stationLoading && (
                      <p className="text-sm text-slate-400 italic">No ground stations within 50km of this location.</p>
                    )}
                    {stationLoading && (
                      <div className="flex items-center gap-2 text-slate-500">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading station data...
                      </div>
                    )}
                  </div>
                </Card>

                <Card className="p-5">
                  <h3 className="text-lg font-bold text-blue-400 flex items-center gap-2 mb-4">
                    <Wind className="w-5 h-5" /> 72-Hour Hourly Trend
                  </h3>
                  <div className="h-[320px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={(hourlyTrend || []).slice().reverse()}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                        <XAxis
                          dataKey="timestamp"
                          stroke="#94A3B8"
                          fontSize={10}
                          tickFormatter={(value) => new Date(value).toLocaleTimeString(undefined, { hour: 'numeric', day: 'numeric' })}
                        />
                        <YAxis yAxisId="temp" stroke="#94A3B8" fontSize={11} unit="°C" />
                        <YAxis yAxisId="rain" orientation="right" stroke="#94A3B8" fontSize={11} unit="mm" />
                        <Tooltip
                          contentStyle={{ backgroundColor: '#0F172A', border: '1px solid #334155' }}
                          labelFormatter={(value) => new Date(value).toLocaleString()}
                        />
                        <Legend />
                        <Line yAxisId="temp" type="monotone" dataKey="temperatureC" name="Temperature" stroke={CHART_COLORS.temp} strokeWidth={2} dot={false} />
                        <Bar yAxisId="rain" dataKey="precipitationMm" name="Precipitation" fill={CHART_COLORS.precip} radius={[2, 2, 0, 0]} />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                  {hourlyLoading && <p className="text-xs text-slate-500 mt-2 flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> Loading hourly data...</p>}
                </Card>
              </div>
            </div>
          )}

          <Card className="p-4">
            <h3 className="text-sm font-bold text-slate-400 mb-2 uppercase tracking-wider">Warning Context</h3>
            <div className="flex flex-wrap gap-3 text-sm">
              <Badge variant="warning">Total {analytics.warningHistory?.totalWarnings ?? 0}</Badge>
              <Badge variant="critical">Flood {analytics.warningHistory?.floodWarnings ?? 0}</Badge>
              <Badge variant="info">Landslide {analytics.warningHistory?.landslideWarnings ?? 0}</Badge>
              <span className="text-slate-400">Last warning: {analytics.warningHistory?.lastWarningAt ? new Date(analytics.warningHistory.lastWarningAt).toLocaleString() : 'N/A'}</span>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  );
}
