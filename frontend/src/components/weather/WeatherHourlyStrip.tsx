import React from 'react';
import { useForecast } from '../../hooks/useWeather';
import { getWeatherIcon, mapSymbolToWmo } from './WeatherCard';

interface Props {
  lat: number | null;
  lng: number | null;
  currentTempC?: number | null;
}

export const WeatherHourlyStrip: React.FC<Props> = ({ lat, lng, currentTempC = null }) => {
  const { data, isLoading } = useForecast(lat, lng);

  if (isLoading) {
    return (
      <div className="flex gap-4 overflow-x-auto pb-2 no-scrollbar">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="min-w-[100px] h-32 bg-slate-700/50 animate-pulse rounded-2xl" />
        ))}
      </div>
    );
  }

  // Use the backend shortIntervals if available, fall back to hourly legacy map
  const intervals = data?.shortIntervals ?? [];

  if (intervals.length === 0 && (!data?.hourly?.time || !data?.hourly?.temperature_2m)) {
    const currentCode = data?.current?.weather_code ?? 3;
    const temp = currentTempC ?? data?.current?.temperature_2m;
    if (temp == null) return null;

    return (
      <div className="flex gap-3 overflow-x-auto pb-2 no-scrollbar">
        <div className="min-w-[100px] flex flex-col items-center gap-3 py-4 px-3 rounded-2xl bg-blue-500/20 border border-blue-500/30 shadow-lg">
          <span className="text-xs font-bold uppercase tracking-widest text-blue-300">Now</span>
          <div className="flex h-10 w-10 items-center justify-center text-blue-200">
            {getWeatherIcon(currentCode, 1, 'w-8 h-8')}
          </div>
          <span className="text-lg font-bold text-white">{Math.round(temp)}°</span>
        </div>
      </div>
    );
  }

  // Render shortIntervals with larger icons and proper spacing
  const hours = intervals.slice(0, 24).map((interval: any, idx: number) => {
    const d = new Date(interval.start);
    const label = idx === 0 ? 'Now' : `${String(d.getHours()).padStart(2, '0')}:00`;
    const temp = Math.round(interval.temperature?.value ?? 0);
    
    const isDay = (d.getHours() >= 6 && d.getHours() < 18) ? 1 : 0;
    const code = mapSymbolToWmo(interval.symbolCode?.next1Hour);

    return { label, temp, code, isDay, idx };
  });

  if (hours.length === 0) return null;

  return (
    <div className="flex gap-3 overflow-x-auto pb-2 no-scrollbar snap-x">
      {hours.map((h: any, i: number) => (
        <div
          key={i}
          className={`min-w-[90px] flex flex-col items-center gap-4 py-4 px-2 rounded-2xl snap-center transition-all cursor-default select-none ${
            i === 0
              ? 'bg-gradient-to-b from-blue-500/30 to-blue-600/10 border border-blue-400/40 shadow-lg'
              : 'bg-white/5 border border-white/10 hover:bg-white/10'
          }`}
        >
          <span className={`text-xs font-bold uppercase tracking-widest ${i === 0 ? 'text-blue-300' : 'text-white/60'}`}>
            {h.label}
          </span>
          <div className={`flex items-center justify-center ${i === 0 ? 'text-blue-200' : 'text-slate-300 drop-shadow-md'}`}>
            {getWeatherIcon(h.code, h.isDay, 'w-10 h-10')}
          </div>
          <span className={`text-xl font-bold ${i === 0 ? 'text-white' : 'text-white/90'}`}>
            {h.temp}°
          </span>
        </div>
      ))}
    </div>
  );
};
