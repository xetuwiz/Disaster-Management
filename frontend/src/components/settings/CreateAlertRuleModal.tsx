import React, { useState, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { X, AlertTriangle, MapPin, Sliders, Clock, Info, Bell, Mail, MessageSquare, Smartphone } from 'lucide-react';
import { alertRulesApi } from '../../api/endpoints';
import { extractApiErrorMessage } from '../../api/error';
import Button from '../common/Button';
import Input from '../common/Input';
import { SpatialUnitSearch } from '../common/SpatialUnitSearch';
import { toast } from 'react-hot-toast';

interface CreateAlertRuleModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  initialData?: any;
}

const PARAMETERS = [
  { value: 'temp_c', label: 'Temperature (°C)' },
  { value: 'precipitation_mm', label: 'Precipitation (mm)' },
  { value: 'wind_speed_kmh', label: 'Wind Speed (km/h)' },
  { value: 'cape_jkg', label: 'CAPE Index (J/kg)' },
  { value: 'humidity_pct', label: 'Humidity (%)' },
  { value: 'uv_index', label: 'UV Index' },
  { value: 'pressure_hpa', label: 'Pressure (hPa)' },
  { value: 'cloud_cover_pct', label: 'Cloud Cover (%)' },
];

const OPERATORS = [
  { value: '>', label: 'Greater than' },
  { value: '<', label: 'Less than' },
  { value: '>=', label: 'Greater or equal' },
  { value: '<=', label: 'Less or equal' },
];

const COOLDOWN_OPTIONS = [1, 3, 6, 12, 24];
const FORECAST_WINDOW_OPTIONS = [
  { value: 1, label: 'Current (1 hour)' },
  { value: 3, label: 'Next 3 hours' },
  { value: 6, label: 'Next 6 hours' },
  { value: 12, label: 'Next 12 hours' },
  { value: 24, label: 'Next 24 hours (1 day)' },
  { value: 48, label: 'Next 48 hours (2 days)' },
  { value: 72, label: 'Next 72 hours (3 days)' },
  { value: 168, label: 'Next 7 days' },
];

const AGGREGATION_OPTIONS = [
  { value: 'CURRENT', label: 'Current Value', desc: 'Only check the first hour (now)' },
  { value: 'MAX', label: 'Maximum', desc: 'Alert if ANY hour exceeds threshold' },
  { value: 'MIN', label: 'Minimum', desc: 'Alert if lowest value meets threshold' },
  { value: 'AVG', label: 'Average', desc: 'Alert if average across window exceeds' },
  { value: 'SUM', label: 'Total Sum', desc: 'For accumulated values like rainfall' },
];

const SEVERITY_OPTIONS = [
  { value: 'LOW', label: 'Low' },
  { value: 'MODERATE', label: 'Moderate' },
  { value: 'HIGH', label: 'High' },
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'EXTREME', label: 'Extreme' },
];

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const ALLOWED_PARAMETERS = new Set(PARAMETERS.map((p) => p.value));
const ALLOWED_OPERATORS = new Set(OPERATORS.map((o) => o.value));
const ALLOWED_AGGREGATIONS = new Set(AGGREGATION_OPTIONS.map((a) => a.value));

const CreateAlertRuleModal: React.FC<CreateAlertRuleModalProps> = ({ isOpen, onClose, onSuccess, initialData }) => {
  const [loading, setLoading] = useState(false);
  const [selectedUnit, setSelectedUnit] = useState<any>(null);

  // Update selectedUnit when initialData changes
  useEffect(() => {
    if (initialData) {
      setSelectedUnit({ id: initialData.spatialUnitId, name: initialData.spatialUnitName });
    } else {
      setSelectedUnit(null);
    }
  }, [initialData]);

  const { register, handleSubmit, reset, watch, setValue, formState: { errors } } = useForm({
    defaultValues: {
      name: '',
      parameter: 'temp_c',
      operator: '>',
      threshold: 35,
      cooldownHours: 6,
      forecastWindowHours: 1,
      aggregationType: 'CURRENT',
      severityThreshold: 'MODERATE',
      channels: ['IN_APP', 'EMAIL'],
      timeWindowStart: '',
      timeWindowEnd: '',
    }
  });

  // Reset form when initialData changes (for edit mode)
  useEffect(() => {
    if (initialData) {
      reset({
        name: initialData.name || '',
        parameter: initialData.parameter || 'temp_c',
        operator: initialData.operator || '>',
        threshold: initialData.threshold ?? 35,
        cooldownHours: initialData.cooldownHours ?? 6,
        forecastWindowHours: initialData.forecastWindowHours ?? 1,
        aggregationType: initialData.aggregationType || 'CURRENT',
        severityThreshold: initialData.severityThreshold || 'MODERATE',
        channels: initialData.channels || ['IN_APP', 'EMAIL'],
        timeWindowStart: initialData.timeWindowStart || '',
        timeWindowEnd: initialData.timeWindowEnd || '',
      });
    } else {
      reset({
        name: '',
        parameter: 'temp_c',
        operator: '>',
        threshold: 35,
        cooldownHours: 6,
        forecastWindowHours: 1,
        aggregationType: 'CURRENT',
        severityThreshold: 'MODERATE',
        channels: ['IN_APP', 'EMAIL'],
        timeWindowStart: '',
        timeWindowEnd: '',
      });
    }
  }, [initialData, reset]);

  const aggregationType = watch('aggregationType');
  const channels = watch('channels') || [];

  const toggleChannel = (channel: string) => {
    const current = channels || [];
    let next;
    if (current.includes(channel)) {
      next = current.filter((c: string) => c !== channel);
    } else {
      next = [...current, channel];
    }
    setValue('channels', next);
  };

  if (!isOpen) return null;

  const onSubmit = async (data: any) => {
    if (!selectedUnit) {
      toast.error('Please select a spatial unit');
      return;
    }

    const name = String(data.name || '').trim();
    if (name.length < 3 || name.length > 120) {
      toast.error('Rule name must be 3-120 characters');
      return;
    }

    if (!selectedUnit?.id || !UUID_RE.test(String(selectedUnit.id))) {
      toast.error('Invalid spatial unit selected');
      return;
    }

    if (!ALLOWED_PARAMETERS.has(String(data.parameter))) {
      toast.error('Please select a valid parameter');
      return;
    }

    if (!ALLOWED_OPERATORS.has(String(data.operator))) {
      toast.error('Please select a valid operator');
      return;
    }

    const threshold = Number(data.threshold);
    if (!Number.isFinite(threshold)) {
      toast.error('Please enter a valid threshold value');
      return;
    }

    const cooldownHours = Number.parseInt(String(data.cooldownHours), 10);
    if (!Number.isFinite(cooldownHours) || cooldownHours < 1 || cooldownHours > 168) {
      toast.error('Cooldown must be between 1 and 168 hours');
      return;
    }

    if (data.timeWindowStart && !TIME_RE.test(String(data.timeWindowStart))) {
      toast.error('Start time must be in HH:mm format');
      return;
    }
    if (data.timeWindowEnd && !TIME_RE.test(String(data.timeWindowEnd))) {
      toast.error('End time must be in HH:mm format');
      return;
    }

    // Validate new fields
    const forecastWindowHours = Number(data.forecastWindowHours);
    if (!forecastWindowHours || forecastWindowHours < 1 || forecastWindowHours > 168) {
      toast.error('Forecast window must be between 1 and 168 hours');
      return;
    }

    if (!ALLOWED_AGGREGATIONS.has(String(data.aggregationType))) {
      toast.error('Please select a valid aggregation type');
      return;
    }

    // Fix: convert empty strings to null for time windows
    const payload = {
      ...data,
      name,
      spatialUnitId: selectedUnit.id,
      threshold,
      cooldownHours,
      forecastWindowHours,
      channels: data.channels || ['IN_APP', 'EMAIL'],
      timeWindowStart: data.timeWindowStart || null,
      timeWindowEnd: data.timeWindowEnd || null,
    };

    try {
      setLoading(true);

      if (initialData) {
        await alertRulesApi.updateAlertRule(initialData.id, payload);
        toast.success('Alert rule updated');
      } else {
        await alertRulesApi.createAlertRule(payload);
        toast.success('Alert rule created');
      }
      
      onSuccess();
      onClose();
      reset();
      setSelectedUnit(null);
    } catch (error) {
      toast.error(extractApiErrorMessage(error, 'Failed to save alert rule'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/80 backdrop-blur-sm">
      <div className="bg-slate-800 border border-slate-700 w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden animate-in fade-in zoom-in duration-200">
        <div className="flex items-center justify-between p-6 border-b border-slate-700">
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <AlertTriangle className="text-amber-500" size={20} />
            {initialData ? 'Edit Alert Rule' : 'New Alert Rule'}
          </h2>
          <button onClick={onClose} className="p-2 text-slate-400 hover:text-white hover:bg-slate-700 rounded-lg transition-colors">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="p-6 space-y-4">
          <Input
            label="Rule Name"
            placeholder="e.g., High Heat in Colombo"
            {...register('name', { required: 'Name is required' })}
            error={errors.name?.message as string}
          />

          <div className="space-y-1.5">
            <label className="text-sm font-medium text-slate-400 flex items-center gap-2">
              <MapPin size={14} /> Spatial Unit
            </label>
            {selectedUnit ? (
              <div className="flex items-center justify-between p-3 bg-emerald-500/10 border border-emerald-500/30 rounded-lg">
                <span className="text-emerald-500 font-bold">{selectedUnit.name}</span>
                <button 
                  type="button"
                  onClick={() => setSelectedUnit(null)}
                  className="text-slate-400 hover:text-rose-500"
                >
                  <X size={16} />
                </button>
              </div>
            ) : (
              <SpatialUnitSearch 
                onSelect={(u) => setSelectedUnit(u)} 
                className="bg-slate-900/50 border-slate-700"
              />
            )}
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-slate-400 flex items-center gap-2">
                <Sliders size={14} /> Parameter
              </label>
              <select 
                {...register('parameter')}
                className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-emerald-500/50 outline-none"
              >
                {PARAMETERS.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-sm font-medium text-slate-400">Condition</label>
              <select 
                {...register('operator')}
                className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-emerald-500/50 outline-none"
              >
                {OPERATORS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Threshold Value"
              type="number"
              step="any"
              {...register('threshold', { required: 'Required' })}
            />
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-slate-400 flex items-center gap-2">
                <Clock size={14} /> Cooldown (Hours)
              </label>
              <select 
                {...register('cooldownHours')}
                className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-emerald-500/50 outline-none"
              >
                {COOLDOWN_OPTIONS.map(c => <option key={c} value={c}>{c} Hours</option>)}
              </select>
            </div>
          </div>

          {/* Forecast Window Section */}
          <div className="bg-amber-500/5 p-4 rounded-xl border border-amber-500/20 space-y-4">
            <div className="flex items-center gap-2">
              <Info size={16} className="text-amber-500" />
              <h4 className="text-xs font-bold text-amber-500 uppercase">Forecast Evaluation</h4>
            </div>
            
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-slate-300">Forecast Window</label>
                <select 
                  {...register('forecastWindowHours')}
                  className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-amber-500/50 outline-none"
                >
                  {FORECAST_WINDOW_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
                <p className="text-[10px] text-slate-500">How far ahead to check</p>
              </div>

              <div className="space-y-1.5">
                <label className="text-sm font-medium text-slate-300">Aggregation</label>
                <select 
                  {...register('aggregationType')}
                  className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-amber-500/50 outline-none"
                >
                  {AGGREGATION_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
                <p className="text-[10px] text-slate-500">
                  {AGGREGATION_OPTIONS.find(o => o.value === aggregationType)?.desc}
                </p>
              </div>
            </div>
          </div>

          {/* Severity Threshold & Channels */}
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-slate-400">Minimum Severity</label>
              <select 
                {...register('severityThreshold')}
                className="w-full bg-slate-900/50 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:ring-2 focus:ring-emerald-500/50 outline-none"
              >
                {SEVERITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <p className="text-[10px] text-slate-500">Don't alert below this level</p>
            </div>

            <div className="space-y-1.5">
              <label className="text-sm font-medium text-slate-400">Alert Channels</label>
              <div className="space-y-2 p-3 bg-slate-900/30 rounded-lg border border-slate-700/50">
                {/* In-App */}
                <label
                  className={`flex items-center gap-3 p-2 rounded-lg cursor-pointer transition-all ${
                    channels.includes('IN_APP')
                      ? 'bg-emerald-500/20 border border-emerald-500/40'
                      : 'hover:bg-slate-800/50 border border-transparent'
                  }`}
                  onClick={() => toggleChannel('IN_APP')}
                >
                  <div className={`w-5 h-5 rounded flex items-center justify-center transition-all ${
                    channels.includes('IN_APP')
                      ? 'bg-emerald-500 text-white'
                      : 'border-2 border-slate-500 bg-slate-800'
                  }`}>
                    {channels.includes('IN_APP') && <Bell size={12} />}
                  </div>
                  <Bell size={16} className={channels.includes('IN_APP') ? 'text-emerald-400' : 'text-slate-500'} />
                  <span className={`text-sm ${channels.includes('IN_APP') ? 'text-emerald-300' : 'text-slate-400'}`}>
                    In-App
                  </span>
                </label>

                {/* Email */}
                <label
                  className={`flex items-center gap-3 p-2 rounded-lg cursor-pointer transition-all ${
                    channels.includes('EMAIL')
                      ? 'bg-blue-500/20 border border-blue-500/40'
                      : 'hover:bg-slate-800/50 border border-transparent'
                  }`}
                  onClick={() => toggleChannel('EMAIL')}
                >
                  <div className={`w-5 h-5 rounded flex items-center justify-center transition-all ${
                    channels.includes('EMAIL')
                      ? 'bg-blue-500 text-white'
                      : 'border-2 border-slate-500 bg-slate-800'
                  }`}>
                    {channels.includes('EMAIL') && <Mail size={12} />}
                  </div>
                  <Mail size={16} className={channels.includes('EMAIL') ? 'text-blue-400' : 'text-slate-500'} />
                  <span className={`text-sm ${channels.includes('EMAIL') ? 'text-blue-300' : 'text-slate-400'}`}>
                    Email
                  </span>
                </label>

                {/* SMS */}
                <label
                  className={`flex items-center gap-3 p-2 rounded-lg cursor-pointer transition-all ${
                    channels.includes('SMS')
                      ? 'bg-amber-500/20 border border-amber-500/40'
                      : 'hover:bg-slate-800/50 border border-transparent'
                  }`}
                  onClick={() => toggleChannel('SMS')}
                >
                  <div className={`w-5 h-5 rounded flex items-center justify-center transition-all ${
                    channels.includes('SMS')
                      ? 'bg-amber-500 text-white'
                      : 'border-2 border-slate-500 bg-slate-800'
                  }`}>
                    {channels.includes('SMS') && <Smartphone size={12} />}
                  </div>
                  <Smartphone size={16} className={channels.includes('SMS') ? 'text-amber-400' : 'text-slate-500'} />
                  <span className={`text-sm ${channels.includes('SMS') ? 'text-amber-300' : 'text-slate-400'}`}>
                    SMS
                  </span>
                </label>
              </div>
            </div>
          </div>

          <div className="bg-slate-900/30 p-4 rounded-xl border border-slate-700/50 space-y-3">
            <h4 className="text-xs font-bold text-slate-500 uppercase flex items-center gap-2">
              <Clock size={12} /> Optional Time Window (Daily)
            </h4>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1">
                <label className="text-[10px] font-bold text-slate-500 uppercase">Active From</label>
                <input 
                  type="time" 
                  {...register('timeWindowStart')}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-white focus:ring-1 focus:ring-emerald-500/30 outline-none"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[10px] font-bold text-slate-500 uppercase">Active Until</label>
                <input 
                  type="time" 
                  {...register('timeWindowEnd')}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-sm text-white focus:ring-1 focus:ring-emerald-500/30 outline-none"
                />
              </div>
            </div>
            <p className="text-[10px] text-slate-500">Rule only evaluates during these hours (optional)</p>
          </div>

          <div className="flex gap-3 pt-2">
            <Button 
              type="button" 
              variant="outline" 
              className="flex-1" 
              onClick={onClose}
            >
              Cancel
            </Button>
            <Button 
              type="submit" 
              className="flex-1"
              loading={loading}
            >
              {initialData ? 'Update Rule' : 'Create Rule'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default CreateAlertRuleModal;
