import React from 'react';
import { useForecast } from '../../hooks/useWeather';
import { getWeatherIcon, mapSymbolToWmo } from './WeatherCard';
import { CalendarDays } from 'lucide-react';

interface Props {
  lat: number | null;
  lng: number | null;
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export const WeatherDailyForecast: React.FC<Props> = ({ lat, lng }) => {
  const { data, isLoading } = useForecast(lat, lng);

  if (isLoading) {
    return (
      <div className="bg-white/5 backdrop-blur-xl rounded-2xl border border-white/10 p-5 space-y-4 animate-pulse">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-10 bg-slate-700 rounded" />
        ))}
      </div>
    );
  }

  // Use backend dayIntervals if available
  const intervals = data?.dayIntervals ?? [];

  if (intervals.length === 0 && (!data?.daily?.time || !data?.daily?.temperature_2m_max)) {
    return null;
  }

  // Render dayIntervals with larger icons
  const currentTemp = data?.tempC;
  const days = intervals.slice(0, 7).map((day: any, i: number) => {
    const d = new Date(day.start);
    const label = i === 0 ? 'Today' : DAYS[d.getDay()];
    const minTemp = Math.round(day.temperature?.min ?? 0);
    let maxTemp = Math.round(day.temperature?.max ?? 0);

    // If it's today, ensure maxTemp is at least the current temp
    if (i === 0 && currentTemp != null) {
      maxTemp = Math.max(maxTemp, Math.round(currentTemp));
    }

    const precip = day.precipitation?.value ?? 0;
    const code = mapSymbolToWmo(day.twentyFourHourSymbol);

    return { label, minTemp, maxTemp, precip, code, day: d.getDate() };
  });

  if (days.length === 0) return null;

  const globalMin = Math.min(...days.map((d: any) => d.minTemp));
  const globalMax = Math.max(...days.map((d: any) => d.maxTemp));
  const spread = globalMax - globalMin || 1;

  return (
    <div className="bg-white/5 backdrop-blur-xl rounded-2xl border border-white/10 p-5 shadow-lg">
      <h3 className="text-sm font-bold text-white/70 flex items-center gap-2 mb-4">
        <CalendarDays size={16} className="text-blue-400" />
        {days.length}-Day Forecast
      </h3>

      <div className="space-y-4">
        {days.map((day: any, i: number) => {
          const barLeft = ((day.minTemp - globalMin) / spread) * 100;
          const barWidth = ((day.maxTemp - day.minTemp) / spread) * 100;

          return (
            <div key={i} className="flex items-center gap-4 group">
              {/* Day name and number */}
              <div className="w-14 shrink-0">
                <p className={`text-sm font-bold ${i === 0 ? 'text-white' : 'text-white/60'}`}>
                  {day.label}
                </p>
                <p className="text-xs text-white/40">{day.day}</p>
              </div>

              {/* Icon - now properly mapped and larger */}
              <div className="flex w-12 justify-center text-blue-300 drop-shadow-md shrink-0">
                {getWeatherIcon(day.code, 1, 'w-8 h-8')}
              </div>

              {/* Precip dot */}
              {day.precip > 0.1 && (
                <span className="text-xs font-bold text-cyan-400 w-10 shrink-0 text-right">
                  {day.precip.toFixed(1)}mm
                </span>
              )}
              {day.precip <= 0.1 && <span className="w-10 shrink-0" />}

              {/* Temp bar */}
              <div className="flex-1 flex items-center gap-2 min-w-0">
                <span className="text-xs text-white/40 font-mono w-8 text-right shrink-0">{day.minTemp}°</span>
                <div className="flex-1 relative h-2 bg-white/5 rounded-full overflow-hidden">
                  <div
                    className="absolute h-full rounded-full bg-gradient-to-r from-blue-400 via-emerald-400 to-amber-500 transition-all duration-500"
                    style={{ left: `${barLeft}%`, width: `${Math.max(barWidth, 4)}%` }}
                  />
                </div>
                <span className="text-xs text-white/80 font-bold font-mono w-8 shrink-0">{day.maxTemp}°</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
