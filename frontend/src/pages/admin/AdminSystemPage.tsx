import React, { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  CloudRain,
  Cpu,
  Database,
  Edit2,
  Eye,
  FileJson,
  Info,
  Map,
  Play,
  Plus,
  Radio,
  RefreshCw,
  Search,
  Server,
  Trash2,
  Waves,
  X,
  Zap,
} from 'lucide-react';
import { adminApi } from '../../api/endpoints';

type SpatialType = 'COUNTRY' | 'PROVINCE' | 'DISTRICT' | 'DS_DIVISION' | 'GN_DIVISION';
type WeatherNodeDensity = 'STANDARD' | 'DENSE';

type SpatialUnitForm = {
  name: string;
  nameSinhala: string;
  nameTamil: string;
  pcode: string;
  type: SpatialType;
  lat: string;
  lng: string;
  parentId: string;
  population: string;
  isTracked: boolean;
  isActive: boolean;
};

type WeatherNodeForm = {
  code: string;
  gridKey: string;
  lat: string;
  lng: string;
  elevationM: string;
  zoneDensity: WeatherNodeDensity;
  isCoastal: boolean;
  isMountain: boolean;
  distanceToCoastKm: string;
  isActive: boolean;
  isVolatile: boolean;
};

const initialSpatialForm: SpatialUnitForm = {
  name: '',
  nameSinhala: '',
  nameTamil: '',
  pcode: '',
  type: 'GN_DIVISION',
  lat: '',
  lng: '',
  parentId: '',
  population: '',
  isTracked: true,
  isActive: true,
};

const initialNodeForm: WeatherNodeForm = {
  code: '',
  gridKey: '',
  lat: '',
  lng: '',
  elevationM: '',
  zoneDensity: 'STANDARD',
  isCoastal: false,
  isMountain: false,
  distanceToCoastKm: '',
  isActive: true,
  isVolatile: false,
};

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PCODE_RE = /^LK[0-9]*$/;

const AdminSystemPage = () => {
  const [activeTab, setActiveTab] = useState<'spatial' | 'nodes' | 'workers'>('spatial');

  const [loading, setLoading] = useState(true);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [spatialUnits, setSpatialUnits] = useState<any[]>([]);
  const [weatherNodes, setWeatherNodes] = useState<any[]>([]);
  const [workerStatuses, setWorkerStatuses] = useState<any[]>([]);
  const [syncStatuses, setSyncStatuses] = useState<any[]>([]);

  const [spatialPage, setSpatialPage] = useState(0);
  const [spatialSize, setSpatialSize] = useState(20);
  const [spatialTotalPages, setSpatialTotalPages] = useState(1);
  const [spatialTotalElements, setSpatialTotalElements] = useState(0);
  const [spatialQuery, setSpatialQuery] = useState('');
  const [spatialTypeFilter, setSpatialTypeFilter] = useState('');
  const [spatialActiveFilter, setSpatialActiveFilter] = useState('');
  const [spatialTrackedFilter, setSpatialTrackedFilter] = useState('');
  const [spatialSort, setSpatialSort] = useState('updatedAt,desc');

  const [nodesPage, setNodesPage] = useState(0);
  const [nodesSize, setNodesSize] = useState(20);
  const [nodesTotalPages, setNodesTotalPages] = useState(1);
  const [nodesTotalElements, setNodesTotalElements] = useState(0);
  const [nodesQuery, setNodesQuery] = useState('');
  const [nodesActiveFilter, setNodesActiveFilter] = useState('');
  const [nodesVolatileFilter, setNodesVolatileFilter] = useState('');
  const [nodesCoastalFilter, setNodesCoastalFilter] = useState('');
  const [nodesMountainFilter, setNodesMountainFilter] = useState('');
  const [nodesSort, setNodesSort] = useState('updatedAt,desc');

  const [selectedSpatial, setSelectedSpatial] = useState<any>(null);
  const [spatialDetails, setSpatialDetails] = useState<{ mappings: any[]; insight: any } | null>(null);

  const [selectedNode, setSelectedNode] = useState<any>(null);
  const [nodeTelemetry, setNodeTelemetry] = useState<any>(null);
  const [nodeTelemetrySummary, setNodeTelemetrySummary] = useState<any>(null);

  const [spatialFormOpen, setSpatialFormOpen] = useState(false);
  const [spatialEditing, setSpatialEditing] = useState<any>(null);
  const [spatialForm, setSpatialForm] = useState<SpatialUnitForm>(initialSpatialForm);
  const [spatialFormSaving, setSpatialFormSaving] = useState(false);

  const [nodeFormOpen, setNodeFormOpen] = useState(false);
  const [nodeEditing, setNodeEditing] = useState<any>(null);
  const [nodeForm, setNodeForm] = useState<WeatherNodeForm>(initialNodeForm);
  const [nodeFormSaving, setNodeFormSaving] = useState(false);

  const showMessage = (msg: string, isError = false) => {
    if (isError) setError(msg);
    else setSuccess(msg);
    setTimeout(() => {
      setError('');
      setSuccess('');
    }, 5000);
  };

  const toBooleanOrUndefined = (value: string) => {
    if (value === '') return undefined;
    return value === 'true';
  };

  const loadSpatialUnits = async () => {
    const params: any = {
      page: spatialPage,
      size: spatialSize,
      sort: spatialSort,
    };
    if (spatialQuery.trim()) params.q = spatialQuery.trim();
    if (spatialTypeFilter) params.type = spatialTypeFilter;
    if (spatialActiveFilter !== '') params.isActive = toBooleanOrUndefined(spatialActiveFilter);
    if (spatialTrackedFilter !== '') params.isTracked = toBooleanOrUndefined(spatialTrackedFilter);

    const res = await adminApi.getSpatialUnits(params);
    setSpatialUnits(res.content || []);
    setSpatialTotalPages(res.totalPages || 1);
    setSpatialTotalElements(res.totalElements || 0);
  };

  const loadWeatherNodes = async () => {
    const params: any = {
      page: nodesPage,
      size: nodesSize,
      sort: nodesSort,
    };
    if (nodesQuery.trim()) params.q = nodesQuery.trim();
    if (nodesActiveFilter !== '') params.isActive = toBooleanOrUndefined(nodesActiveFilter);
    if (nodesVolatileFilter !== '') params.isVolatile = toBooleanOrUndefined(nodesVolatileFilter);
    if (nodesCoastalFilter !== '') params.isCoastal = toBooleanOrUndefined(nodesCoastalFilter);
    if (nodesMountainFilter !== '') params.isMountain = toBooleanOrUndefined(nodesMountainFilter);

    const res = await adminApi.getWeatherNodes(params);
    setWeatherNodes(res.content || []);
    setNodesTotalPages(res.totalPages || 1);
    setNodesTotalElements(res.totalElements || 0);
  };

  const loadWorkers = async () => {
    const res = await adminApi.getWorkersStatus();
    setWorkerStatuses(Array.isArray(res) ? res : []);
  };

  const loadSyncStatuses = async () => {
    try {
      const res = await adminApi.getSyncStatus();
      setSyncStatuses(Array.isArray(res) ? res : []);
    } catch (e) {
      console.error("Failed to load sync statuses", e);
    }
  };

  const loadData = async () => {
    setLoading(true);
    setError('');
    try {
      if (activeTab === 'spatial') {
        await loadSpatialUnits();
      } else if (activeTab === 'nodes') {
        await loadWeatherNodes();
      } else {
        await Promise.all([loadWorkers(), loadSyncStatuses()]);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [
    activeTab,
    spatialPage,
    spatialSize,
    spatialSort,
    nodesPage,
    nodesSize,
    nodesSort,
  ]);

  const runSpatialSearch = async () => {
    setSpatialPage(0);
    if (activeTab === 'spatial') {
      setTimeout(() => loadData(), 0);
    }
  };

  const runNodesSearch = async () => {
    setNodesPage(0);
    if (activeTab === 'nodes') {
      setTimeout(() => loadData(), 0);
    }
  };

  const handleTrigger = async (action: string, workerKey: string, days?: number) => {
    try {
      setSuccess('');
      setError('');
      const res = await adminApi.runWorker(workerKey, days ? { days } : undefined);
      setSuccess(`${action} triggered successfully: ${res.message || 'Started'}`);
      if (activeTab === 'workers') {
        await loadWorkers();
      }
    } catch (err: any) {
      setError(`Failed to trigger ${action}: ${err.response?.data?.message || err.message}`);
    }
  };

  const handleSyncAction = async (actionType: 'run' | 'reset', jobName: string) => {
    try {
      setSuccess('');
      setError('');
      if (actionType === 'run') {
        await adminApi.runSyncJob(jobName);
        setSuccess(`Job ${jobName} force-run triggered.`);
      } else {
        await adminApi.resetSyncCooldown(jobName);
        setSuccess(`Job ${jobName} cooldown reset.`);
      }
      await loadSyncStatuses();
    } catch (err: any) {
      setError(`Failed to ${actionType} job ${jobName}: ${err.response?.data?.message || err.message}`);
    }
  };

  const handleViewSpatialDetails = async (unit: any) => {
    setSelectedSpatial(unit);
    setDetailsLoading(true);
    try {
      const [mappings, insight] = await Promise.all([
        adminApi.getSpatialUnitMappings(unit.id, 10),
        adminApi.getSpatialUnitWeatherInsight(unit.id, 5).catch(() => null),
      ]);
      setSpatialDetails({ mappings, insight });
    } catch {
      showMessage('Failed to load spatial details', true);
    } finally {
      setDetailsLoading(false);
    }
  };

  const handleViewNodeTelemetry = async (node: any) => {
    setSelectedNode(node);
    setDetailsLoading(true);
    try {
      const [telemetry, summary] = await Promise.all([
        adminApi.getWeatherNodeLiveTelemetry(node.id),
        adminApi.getWeatherNodeLiveTelemetrySummary(node.id).catch(() => null),
      ]);
      setNodeTelemetry(telemetry);
      setNodeTelemetrySummary(summary);
    } catch {
      showMessage('Failed to load live telemetry. Node might be unsynced.', true);
    } finally {
      setDetailsLoading(false);
    }
  };

  const openCreateSpatial = () => {
    setSpatialEditing(null);
    setSpatialForm(initialSpatialForm);
    setSpatialFormOpen(true);
  };

  const openEditSpatial = (unit: any) => {
    setSpatialEditing(unit);
    setSpatialForm({
      name: unit.name || '',
      nameSinhala: unit.nameSinhala || '',
      nameTamil: unit.nameTamil || '',
      pcode: unit.pcode || '',
      type: unit.type || 'GN_DIVISION',
      lat: unit.lat != null ? String(unit.lat) : '',
      lng: unit.lng != null ? String(unit.lng) : '',
      parentId: unit.parentId || '',
      population: unit.population != null ? String(unit.population) : '',
      isTracked: Boolean(unit.isTracked),
      isActive: Boolean(unit.isActive),
    });
    setSpatialFormOpen(true);
  };

  const submitSpatialForm = async () => {
    if (!spatialForm.name.trim() || !spatialForm.pcode.trim()) {
      showMessage('Name and pcode are required for spatial unit.', true);
      return;
    }

    if (!PCODE_RE.test(spatialForm.pcode.trim())) {
      showMessage('Pcode must start with LK (e.g., LK1103005).', true);
      return;
    }

    const lat = spatialForm.lat === '' ? null : Number(spatialForm.lat);
    const lng = spatialForm.lng === '' ? null : Number(spatialForm.lng);
    const population = spatialForm.population === '' ? null : Number(spatialForm.population);

    if (lat != null && (!Number.isFinite(lat) || lat < -90 || lat > 90)) {
      showMessage('Latitude must be between -90 and 90.', true);
      return;
    }
    if (lng != null && (!Number.isFinite(lng) || lng < -180 || lng > 180)) {
      showMessage('Longitude must be between -180 and 180.', true);
      return;
    }
    if (population != null && (!Number.isFinite(population) || population < 0)) {
      showMessage('Population must be a non-negative number.', true);
      return;
    }
    if (spatialForm.parentId.trim() && !UUID_RE.test(spatialForm.parentId.trim())) {
      showMessage('Parent ID must be a valid UUID.', true);
      return;
    }

    setSpatialFormSaving(true);
    try {
      const payload = {
        name: spatialForm.name.trim(),
        nameSinhala: spatialForm.nameSinhala.trim() || null,
        nameTamil: spatialForm.nameTamil.trim() || null,
        pcode: spatialForm.pcode.trim(),
        type: spatialForm.type,
        lat,
        lng,
        parentId: spatialForm.parentId.trim() || null,
        population,
        isTracked: spatialForm.isTracked,
        isActive: spatialForm.isActive,
      };

      if (spatialEditing) {
        await adminApi.updateSpatialUnit(spatialEditing.id, payload);
        showMessage('Spatial unit updated successfully');
      } else {
        await adminApi.createSpatialUnit(payload);
        showMessage('Spatial unit created successfully');
      }
      setSpatialFormOpen(false);
      await loadSpatialUnits();
    } catch (err: any) {
      showMessage(err.response?.data?.message || 'Failed to save spatial unit', true);
    } finally {
      setSpatialFormSaving(false);
    }
  };

  const openCreateNode = () => {
    setNodeEditing(null);
    setNodeForm(initialNodeForm);
    setNodeFormOpen(true);
  };

  const openEditNode = (node: any) => {
    setNodeEditing(node);
    setNodeForm({
      code: node.code || '',
      gridKey: node.gridKey || '',
      lat: node.lat != null ? String(node.lat) : '',
      lng: node.lng != null ? String(node.lng) : '',
      elevationM: node.elevationM != null ? String(node.elevationM) : '',
      zoneDensity: node.zoneDensity || 'STANDARD',
      isCoastal: Boolean(node.isCoastal),
      isMountain: Boolean(node.isMountain),
      distanceToCoastKm: node.distanceToCoastKm != null ? String(node.distanceToCoastKm) : '',
      isActive: Boolean(node.isActive),
      isVolatile: Boolean(node.isVolatile),
    });
    setNodeFormOpen(true);
  };

  const submitNodeForm = async () => {
    if (!nodeForm.code.trim() || !nodeForm.gridKey.trim() || nodeForm.lat === '' || nodeForm.lng === '') {
      showMessage('Code, gridKey, lat and lng are required for weather node.', true);
      return;
    }

    const lat = Number(nodeForm.lat);
    const lng = Number(nodeForm.lng);
    const elevationM = nodeForm.elevationM === '' ? null : Number(nodeForm.elevationM);
    const distanceToCoastKm = nodeForm.distanceToCoastKm === '' ? null : Number(nodeForm.distanceToCoastKm);

    if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
      showMessage('Latitude must be between -90 and 90.', true);
      return;
    }
    if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
      showMessage('Longitude must be between -180 and 180.', true);
      return;
    }
    if (elevationM != null && (!Number.isFinite(elevationM) || elevationM < -500)) {
      showMessage('Elevation must be greater than or equal to -500 m.', true);
      return;
    }
    if (distanceToCoastKm != null && (!Number.isFinite(distanceToCoastKm) || distanceToCoastKm < 0)) {
      showMessage('Distance to coast must be a non-negative number.', true);
      return;
    }

    setNodeFormSaving(true);
    try {
      const payload = {
        code: nodeForm.code.trim(),
        gridKey: nodeForm.gridKey.trim(),
        lat,
        lng,
        elevationM,
        zoneDensity: nodeForm.zoneDensity,
        isCoastal: nodeForm.isCoastal,
        isMountain: nodeForm.isMountain,
        distanceToCoastKm,
        isActive: nodeForm.isActive,
        isVolatile: nodeForm.isVolatile,
      };

      if (nodeEditing) {
        await adminApi.updateWeatherNode(nodeEditing.id, payload);
        showMessage('Weather node updated successfully');
      } else {
        await adminApi.createWeatherNode(payload);
        showMessage('Weather node created successfully');
      }
      setNodeFormOpen(false);
      await loadWeatherNodes();
    } catch (err: any) {
      showMessage(err.response?.data?.message || 'Failed to save weather node', true);
    } finally {
      setNodeFormSaving(false);
    }
  };

  const parsedTelemetry = useMemo(() => {
    if (!nodeTelemetry?.rawPayload) return null;
    try {
      return JSON.parse(nodeTelemetry.rawPayload);
    } catch {
      return null;
    }
  }, [nodeTelemetry]);

  const telemetryArrayInfo = useMemo(() => {
    if (!Array.isArray(parsedTelemetry)) return null;
    const first = parsedTelemetry[0];
    const current = first?.current || null;
    return {
      count: parsedTelemetry.length,
      firstTemp: current?.temperature_2m,
      firstHumidity: current?.relative_humidity_2m,
      firstWeatherCode: current?.weather_code,
      firstTime: current?.time,
    };
  }, [parsedTelemetry]);

  const renderSpatialTab = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h3 className="text-xl font-bold text-white">Spatial Units</h3>
          <p className="text-slate-400 text-sm">Manage geographical regions, GN divisions, and districts.</p>
        </div>
        <button
          onClick={openCreateSpatial}
          className="flex items-center gap-2 bg-emerald-600 hover:bg-emerald-500 text-white px-4 py-2 rounded-lg transition-colors font-medium"
        >
          <Plus className="w-4 h-4" /> Add Unit
        </button>
      </div>

      <div className="bg-slate-800/40 border border-slate-700 rounded-xl p-4 grid grid-cols-1 md:grid-cols-6 gap-3">
        <div className="md:col-span-2 relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={spatialQuery}
            onChange={(e) => setSpatialQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && runSpatialSearch()}
            placeholder="Search name/pcode"
            className="w-full pl-9 pr-3 py-2 bg-slate-900 border border-slate-700 rounded text-slate-200"
          />
        </div>
        <select value={spatialTypeFilter} onChange={(e) => setSpatialTypeFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Types</option>
          <option value="COUNTRY">COUNTRY</option>
          <option value="PROVINCE">PROVINCE</option>
          <option value="DISTRICT">DISTRICT</option>
          <option value="DS_DIVISION">DS_DIVISION</option>
          <option value="GN_DIVISION">GN_DIVISION</option>
        </select>
        <select value={spatialActiveFilter} onChange={(e) => setSpatialActiveFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Active</option>
          <option value="true">Active</option>
          <option value="false">Inactive</option>
        </select>
        <select value={spatialTrackedFilter} onChange={(e) => setSpatialTrackedFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Tracked</option>
          <option value="true">Tracked</option>
          <option value="false">Untracked</option>
        </select>
        <select value={spatialSort} onChange={(e) => setSpatialSort(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="updatedAt,desc">Updated Desc</option>
          <option value="updatedAt,asc">Updated Asc</option>
          <option value="name,asc">Name Asc</option>
          <option value="name,desc">Name Desc</option>
          <option value="pcode,asc">PCode Asc</option>
          <option value="pcode,desc">PCode Desc</option>
        </select>
        <div className="md:col-span-6 flex gap-2">
          <button onClick={runSpatialSearch} className="px-3 py-2 bg-blue-600 hover:bg-blue-500 rounded text-white text-sm">Apply Filters</button>
          <button
            onClick={() => {
              setSpatialQuery('');
              setSpatialTypeFilter('');
              setSpatialActiveFilter('');
              setSpatialTrackedFilter('');
              setSpatialSort('updatedAt,desc');
              setSpatialPage(0);
              setTimeout(() => loadSpatialUnits(), 0);
            }}
            className="px-3 py-2 bg-slate-700 hover:bg-slate-600 rounded text-slate-200 text-sm"
          >
            Reset
          </button>
        </div>
      </div>

      <div className="bg-slate-800/50 rounded-xl border border-slate-700/50 overflow-hidden">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-slate-800 text-slate-400 font-medium">
            <tr>
              <th className="px-6 py-4">Name</th>
              <th className="px-6 py-4">PCode</th>
              <th className="px-6 py-4">Type</th>
              <th className="px-6 py-4">Location</th>
              <th className="px-6 py-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-700/50">
            {loading ? (
              <tr><td colSpan={5} className="px-6 py-8 text-center"><RefreshCw className="w-6 h-6 animate-spin mx-auto text-emerald-500" /></td></tr>
            ) : spatialUnits.length === 0 ? (
              <tr><td colSpan={5} className="px-6 py-8 text-center text-slate-500">No spatial units found</td></tr>
            ) : (
              spatialUnits.map((u) => (
                <tr key={u.id} className="hover:bg-slate-700/30 transition-colors">
                  <td className="px-6 py-4 font-medium text-slate-200">{u.name}</td>
                  <td className="px-6 py-4 font-mono text-xs">{u.pcode}</td>
                  <td className="px-6 py-4"><span className="px-2.5 py-1 bg-slate-700 text-slate-300 rounded text-xs">{u.type}</span></td>
                  <td className="px-6 py-4 font-mono text-xs text-slate-400">{u.lat?.toFixed?.(4)}, {u.lng?.toFixed?.(4)}</td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex justify-end gap-2">
                      <button onClick={() => handleViewSpatialDetails(u)} className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-emerald-400 transition-colors" title="View IDW & Weather Details"><Eye className="w-4 h-4" /></button>
                      <button onClick={() => openEditSpatial(u)} className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-blue-400 transition-colors" title="Edit Spatial Unit"><Edit2 className="w-4 h-4" /></button>
                      <button
                        onClick={async () => {
                          if (window.confirm('Delete this unit?')) {
                            try {
                              await adminApi.deleteSpatialUnit(u.id);
                              await loadSpatialUnits();
                              showMessage('Deleted successfully');
                            } catch (e: any) {
                              showMessage(e.response?.data?.message || 'Delete failed', true);
                            }
                          }
                        }}
                        className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-red-400 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-slate-400">Total: {spatialTotalElements}</p>
        <div className="flex items-center gap-2">
          <select value={spatialSize} onChange={(e) => { setSpatialSize(Number(e.target.value)); setSpatialPage(0); }} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-1 text-sm">
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
          </select>
          <button disabled={spatialPage <= 0} onClick={() => setSpatialPage((p) => Math.max(0, p - 1))} className="px-3 py-1 bg-slate-700 disabled:opacity-40 rounded text-sm">Prev</button>
          <span className="text-sm text-slate-300">Page {spatialPage + 1} / {Math.max(1, spatialTotalPages)}</span>
          <button disabled={spatialPage + 1 >= spatialTotalPages} onClick={() => setSpatialPage((p) => p + 1)} className="px-3 py-1 bg-slate-700 disabled:opacity-40 rounded text-sm">Next</button>
        </div>
      </div>
    </div>
  );

  const renderNodesTab = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h3 className="text-xl font-bold text-white">Weather Nodes</h3>
          <p className="text-slate-400 text-sm">Manage telemetry nodes and Open-Meteo sync virtual stations.</p>
        </div>
        <button onClick={openCreateNode} className="flex items-center gap-2 bg-emerald-600 hover:bg-emerald-500 text-white px-4 py-2 rounded-lg transition-colors font-medium">
          <Plus className="w-4 h-4" /> Add Node
        </button>
      </div>

      <div className="bg-slate-800/40 border border-slate-700 rounded-xl p-4 grid grid-cols-1 md:grid-cols-6 gap-3">
        <div className="md:col-span-2 relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={nodesQuery}
            onChange={(e) => setNodesQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && runNodesSearch()}
            placeholder="Search code/grid"
            className="w-full pl-9 pr-3 py-2 bg-slate-900 border border-slate-700 rounded text-slate-200"
          />
        </div>
        <select value={nodesActiveFilter} onChange={(e) => setNodesActiveFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Active</option>
          <option value="true">Active</option>
          <option value="false">Inactive</option>
        </select>
        <select value={nodesVolatileFilter} onChange={(e) => setNodesVolatileFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Volatile</option>
          <option value="true">Volatile</option>
          <option value="false">Not Volatile</option>
        </select>
        <select value={nodesCoastalFilter} onChange={(e) => setNodesCoastalFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Coastal</option>
          <option value="true">Coastal</option>
          <option value="false">Inland</option>
        </select>
        <select value={nodesMountainFilter} onChange={(e) => setNodesMountainFilter(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2">
          <option value="">All Mountain</option>
          <option value="true">Mountain</option>
          <option value="false">Non-Mountain</option>
        </select>
        <select value={nodesSort} onChange={(e) => setNodesSort(e.target.value)} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-2 md:col-span-2">
          <option value="updatedAt,desc">Updated Desc</option>
          <option value="updatedAt,asc">Updated Asc</option>
          <option value="code,asc">Code Asc</option>
          <option value="code,desc">Code Desc</option>
          <option value="lat,asc">Lat Asc</option>
          <option value="lat,desc">Lat Desc</option>
        </select>
        <div className="md:col-span-4 flex gap-2">
          <button onClick={runNodesSearch} className="px-3 py-2 bg-blue-600 hover:bg-blue-500 rounded text-white text-sm">Apply Filters</button>
          <button
            onClick={() => {
              setNodesQuery('');
              setNodesActiveFilter('');
              setNodesVolatileFilter('');
              setNodesCoastalFilter('');
              setNodesMountainFilter('');
              setNodesSort('updatedAt,desc');
              setNodesPage(0);
              setTimeout(() => loadWeatherNodes(), 0);
            }}
            className="px-3 py-2 bg-slate-700 hover:bg-slate-600 rounded text-slate-200 text-sm"
          >
            Reset
          </button>
        </div>
      </div>

      <div className="bg-slate-800/50 rounded-xl border border-slate-700/50 overflow-hidden">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-slate-800 text-slate-400 font-medium">
            <tr>
              <th className="px-6 py-4">Code</th>
              <th className="px-6 py-4">Coordinate</th>
              <th className="px-6 py-4">Status</th>
              <th className="px-6 py-4">Density</th>
              <th className="px-6 py-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-700/50">
            {loading ? (
              <tr><td colSpan={5} className="px-6 py-8 text-center"><RefreshCw className="w-6 h-6 animate-spin mx-auto text-emerald-500" /></td></tr>
            ) : weatherNodes.length === 0 ? (
              <tr><td colSpan={5} className="px-6 py-8 text-center text-slate-500">No weather nodes found</td></tr>
            ) : (
              weatherNodes.map((n) => (
                <tr key={n.id} className="hover:bg-slate-700/30 transition-colors">
                  <td className="px-6 py-4 font-mono font-medium text-emerald-400">{n.code}</td>
                  <td className="px-6 py-4 font-mono text-xs text-slate-400">{n.lat?.toFixed?.(4)}, {n.lng?.toFixed?.(4)}</td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${n.isActive ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'}`}>{n.isActive ? 'ACTIVE' : 'INACTIVE'}</span>
                      {n.isVolatile && <span className="px-2 py-0.5 rounded text-xs font-medium bg-amber-500/10 text-amber-400">VOLATILE</span>}
                    </div>
                  </td>
                  <td className="px-6 py-4"><span className="px-2.5 py-1 bg-slate-700 text-slate-300 rounded text-xs">{n.zoneDensity}</span></td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex justify-end gap-2">
                      <button onClick={() => handleViewNodeTelemetry(n)} className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-cyan-400 transition-colors" title="View Live Telemetry Payload"><FileJson className="w-4 h-4" /></button>
                      <button onClick={() => openEditNode(n)} className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-blue-400 transition-colors" title="Edit Weather Node"><Edit2 className="w-4 h-4" /></button>
                      <button
                        onClick={async () => {
                          if (window.confirm('Delete this node?')) {
                            try {
                              await adminApi.deleteWeatherNode(n.id);
                              await loadWeatherNodes();
                              showMessage('Deleted successfully');
                            } catch (e: any) {
                              showMessage(e.response?.data?.message || 'Delete failed', true);
                            }
                          }
                        }}
                        className="p-2 hover:bg-slate-700 rounded-lg text-slate-400 hover:text-red-400 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-slate-400">Total: {nodesTotalElements}</p>
        <div className="flex items-center gap-2">
          <select value={nodesSize} onChange={(e) => { setNodesSize(Number(e.target.value)); setNodesPage(0); }} className="bg-slate-900 border border-slate-700 rounded text-slate-200 px-2 py-1 text-sm">
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
          </select>
          <button disabled={nodesPage <= 0} onClick={() => setNodesPage((p) => Math.max(0, p - 1))} className="px-3 py-1 bg-slate-700 disabled:opacity-40 rounded text-sm">Prev</button>
          <span className="text-sm text-slate-300">Page {nodesPage + 1} / {Math.max(1, nodesTotalPages)}</span>
          <button disabled={nodesPage + 1 >= nodesTotalPages} onClick={() => setNodesPage((p) => p + 1)} className="px-3 py-1 bg-slate-700 disabled:opacity-40 rounded text-sm">Next</button>
        </div>
      </div>
    </div>
  );

  const renderWorkersTab = () => (
    <div className="space-y-10">
      {/* Structural Setup Tasks */}
      <div>
        <div className="mb-6 flex items-center justify-between gap-4">
          <div>
            <h3 className="text-xl font-bold text-white">Structural Setup Tasks</h3>
            <p className="text-slate-400 text-sm">Manually trigger one-time data import and structural tasks.</p>
          </div>
          <button onClick={loadWorkers} className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 text-slate-200 px-4 py-2 rounded-lg transition-colors text-sm font-medium">
            <RefreshCw className="w-4 h-4" /> Refresh Status
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {[
            { icon: Map, key: 'import-spatial', name: 'Import Spatial Data', desc: 'Syncs Sri Lanka districts and GN divisions' },
            { icon: Cpu, key: 'generate-nodes', name: 'Generate Nodes', desc: 'Regenerates the weather grid layout' },
            { icon: Activity, key: 'compute-idw', name: 'Compute IDW', desc: 'Forces IDW recomputation for all units' },
            { icon: Database, key: 'backfill-history', days: 730, name: 'Backfill History', desc: 'Backfills historical weather observations (default 730 days)' },
          ].map((worker, i) => {
            const status = workerStatuses.find((s) => s.workerKey === worker.key);
            return (
              <div key={i} className="bg-slate-800/50 border border-slate-700/50 p-5 rounded-xl flex flex-col sm:flex-row gap-4 items-start sm:items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className="w-10 h-10 rounded-lg bg-slate-700/50 flex items-center justify-center text-slate-300">
                    <worker.icon className="w-5 h-5" />
                  </div>
                  <div>
                    <h4 className="text-slate-200 font-medium">{worker.name}</h4>
                    <p className="text-xs text-slate-400 mt-1">{worker.desc}</p>
                    {status && (
                      <div className="mt-2 text-[11px] text-slate-400 space-y-1">
                        <p>State: <span className={`${status.staleMinutes == null || status.staleMinutes <= 120 ? 'text-emerald-400' : 'text-amber-400'}`}>{status.staleMinutes == null ? 'Unknown' : `${status.staleMinutes} min stale`}</span></p>
                      </div>
                    )}
                  </div>
                </div>
                <button onClick={() => handleTrigger(worker.name, worker.key, worker.days)} className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 text-slate-200 px-4 py-2 rounded-lg transition-colors text-sm font-medium w-full sm:w-auto justify-center">
                  <Play className="w-4 h-4 text-emerald-400" /> Run Task
                </button>
              </div>
            );
          })}
        </div>
      </div>

      {/* Synchronization Dashboards */}
      <div>
        <div className="mb-6 flex items-center justify-between gap-4">
          <div>
            <h3 className="text-xl font-bold text-white">Synchronization Dashboards</h3>
            <p className="text-slate-400 text-sm">Monitor and trigger background recurring synchronization jobs.</p>
          </div>
          <button onClick={loadSyncStatuses} className="flex items-center gap-2 bg-slate-700 hover:bg-slate-600 text-slate-200 px-4 py-2 rounded-lg transition-colors text-sm font-medium">
            <RefreshCw className="w-4 h-4" /> Refresh Dashboards
          </button>
        </div>

        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
          <table className="w-full text-left text-sm text-slate-300">
            <thead className="text-xs text-slate-400 uppercase bg-slate-900/50">
              <tr>
                <th className="px-6 py-4 font-semibold tracking-wider">Job Name</th>
                <th className="px-6 py-4 font-semibold tracking-wider">Status</th>
                <th className="px-6 py-4 font-semibold tracking-wider">Last Run</th>
                <th className="px-6 py-4 font-semibold tracking-wider">Next Run</th>
                <th className="px-6 py-4 font-semibold tracking-wider text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/50">
              {syncStatuses.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-6 py-8 text-center text-slate-400">
                    No sync jobs found.
                  </td>
                </tr>
              ) : (
                syncStatuses.map((job) => (
                  <tr key={job.jobName} className="hover:bg-slate-700/20 transition-colors">
                    <td className="px-6 py-4 font-medium text-slate-200">{job.jobName}</td>
                    <td className="px-6 py-4">
                      {job.errorCount > 0 ? (
                        <span className="text-red-400 flex items-center gap-1">
                          <AlertTriangle className="w-4 h-4" /> Failed ({job.errorCount})
                        </span>
                      ) : job.manualOverride ? (
                        <span className="text-amber-400">Override Active</span>
                      ) : (
                        <span className="text-emerald-400 flex items-center gap-1">
                          <CheckCircle2 className="w-4 h-4" /> Healthy
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-slate-400">{job.lastSuccessUtc ? new Date(job.lastSuccessUtc).toLocaleString() : 'Never'}</td>
                    <td className="px-6 py-4 text-slate-400">{job.nextAllowedUtc ? new Date(job.nextAllowedUtc).toLocaleString() : 'Pending'}</td>
                    <td className="px-6 py-4">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => handleSyncAction('run', job.jobName)}
                          className="px-3 py-1.5 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 rounded transition-colors text-xs font-medium flex items-center gap-1"
                        >
                          <Play className="w-3 h-3" /> Force Run
                        </button>
                        <button
                          onClick={() => handleSyncAction('reset', job.jobName)}
                          className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded transition-colors text-xs font-medium flex items-center gap-1"
                        >
                          <RefreshCw className="w-3 h-3" /> Reset Cooldown
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );

  return (
    <div className="p-6 md:p-8 max-w-7xl mx-auto space-y-8">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <h1 className="text-3xl font-bold text-white tracking-tight flex items-center gap-3">
            <Server className="w-8 h-8 text-emerald-500" />
            Core System
            <span className="px-2.5 py-1 bg-red-500/10 text-red-500 border border-red-500/20 rounded-md text-xs tracking-wider uppercase ml-2 select-none">
              God Mode
            </span>
          </h1>
          <p className="text-slate-400 mt-2 text-sm">System infrastructure, nodes, and worker management.</p>
        </div>
      </div>

      {error && (
        <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} className="p-4 bg-red-500/10 border border-red-500/20 rounded-xl flex items-start gap-3">
          <AlertCircle className="w-5 h-5 text-red-400 shrink-0" />
          <p className="text-red-200 text-sm">{error}</p>
        </motion.div>
      )}
      {success && (
        <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} className="p-4 bg-emerald-500/10 border border-emerald-500/20 rounded-xl flex items-start gap-3">
          <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0" />
          <p className="text-emerald-200 text-sm">{success}</p>
        </motion.div>
      )}

      <div className="bg-slate-800/30 p-1.5 rounded-xl border border-slate-700/50 flex flex-wrap gap-2 w-max max-w-full overflow-x-auto">
        <button onClick={() => setActiveTab('spatial')} className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'spatial' ? 'bg-slate-700 text-white shadow-sm' : 'text-slate-400 hover:text-slate-300 hover:bg-slate-800/80'}`}><Map className="w-4 h-4" /> Spatial Units</button>
        <button onClick={() => setActiveTab('nodes')} className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'nodes' ? 'bg-slate-700 text-white shadow-sm' : 'text-slate-400 hover:text-slate-300 hover:bg-slate-800/80'}`}><Activity className="w-4 h-4" /> Weather Nodes</button>
        <button onClick={() => setActiveTab('workers')} className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'workers' ? 'bg-slate-700 text-white shadow-sm' : 'text-slate-400 hover:text-slate-300 hover:bg-slate-800/80'}`}><Cpu className="w-4 h-4" /> System Workers</button>
      </div>

      <motion.div key={activeTab} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}>
        {activeTab === 'spatial' && renderSpatialTab()}
        {activeTab === 'nodes' && renderNodesTab()}
        {activeTab === 'workers' && renderWorkersTab()}
      </motion.div>

      {spatialFormOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-3xl max-h-[90vh] overflow-y-auto shadow-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xl font-bold text-white">{spatialEditing ? 'Edit Spatial Unit' : 'Create Spatial Unit'}</h3>
              <button onClick={() => setSpatialFormOpen(false)} className="p-2 text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded-xl transition-colors"><X className="w-5 h-5" /></button>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <input value={spatialForm.name} onChange={(e) => setSpatialForm((s) => ({ ...s, name: e.target.value }))} placeholder="name" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input value={spatialForm.pcode} onChange={(e) => setSpatialForm((s) => ({ ...s, pcode: e.target.value }))} placeholder="pcode" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input value={spatialForm.nameSinhala} onChange={(e) => setSpatialForm((s) => ({ ...s, nameSinhala: e.target.value }))} placeholder="nameSinhala" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input value={spatialForm.nameTamil} onChange={(e) => setSpatialForm((s) => ({ ...s, nameTamil: e.target.value }))} placeholder="nameTamil" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <select value={spatialForm.type} onChange={(e) => setSpatialForm((s) => ({ ...s, type: e.target.value as SpatialType }))} className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200">
                <option value="COUNTRY">COUNTRY</option>
                <option value="PROVINCE">PROVINCE</option>
                <option value="DISTRICT">DISTRICT</option>
                <option value="DS_DIVISION">DS_DIVISION</option>
                <option value="GN_DIVISION">GN_DIVISION</option>
              </select>
              <input value={spatialForm.parentId} onChange={(e) => setSpatialForm((s) => ({ ...s, parentId: e.target.value }))} placeholder="parentId (optional UUID)" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={spatialForm.lat} onChange={(e) => setSpatialForm((s) => ({ ...s, lat: e.target.value }))} placeholder="lat" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={spatialForm.lng} onChange={(e) => setSpatialForm((s) => ({ ...s, lng: e.target.value }))} placeholder="lng" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={spatialForm.population} onChange={(e) => setSpatialForm((s) => ({ ...s, population: e.target.value }))} placeholder="population" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <div className="flex items-center gap-4">
                <label className="text-sm text-slate-300"><input type="checkbox" checked={spatialForm.isTracked} onChange={(e) => setSpatialForm((s) => ({ ...s, isTracked: e.target.checked }))} className="mr-2" />isTracked</label>
                <label className="text-sm text-slate-300"><input type="checkbox" checked={spatialForm.isActive} onChange={(e) => setSpatialForm((s) => ({ ...s, isActive: e.target.checked }))} className="mr-2" />isActive</label>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setSpatialFormOpen(false)} className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded">Cancel</button>
              <button onClick={submitSpatialForm} disabled={spatialFormSaving} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded disabled:opacity-40">{spatialFormSaving ? 'Saving...' : 'Save'}</button>
            </div>
          </motion.div>
        </div>
      )}

      {nodeFormOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-3xl max-h-[90vh] overflow-y-auto shadow-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xl font-bold text-white">{nodeEditing ? 'Edit Weather Node' : 'Create Weather Node'}</h3>
              <button onClick={() => setNodeFormOpen(false)} className="p-2 text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded-xl transition-colors"><X className="w-5 h-5" /></button>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <input value={nodeForm.code} onChange={(e) => setNodeForm((s) => ({ ...s, code: e.target.value }))} placeholder="code" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input value={nodeForm.gridKey} onChange={(e) => setNodeForm((s) => ({ ...s, gridKey: e.target.value }))} placeholder="gridKey" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={nodeForm.lat} onChange={(e) => setNodeForm((s) => ({ ...s, lat: e.target.value }))} placeholder="lat" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={nodeForm.lng} onChange={(e) => setNodeForm((s) => ({ ...s, lng: e.target.value }))} placeholder="lng" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={nodeForm.elevationM} onChange={(e) => setNodeForm((s) => ({ ...s, elevationM: e.target.value }))} placeholder="elevationM" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <input type="number" value={nodeForm.distanceToCoastKm} onChange={(e) => setNodeForm((s) => ({ ...s, distanceToCoastKm: e.target.value }))} placeholder="distanceToCoastKm" className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200" />
              <select value={nodeForm.zoneDensity} onChange={(e) => setNodeForm((s) => ({ ...s, zoneDensity: e.target.value as WeatherNodeDensity }))} className="bg-slate-800 border border-slate-700 rounded px-3 py-2 text-slate-200">
                <option value="STANDARD">STANDARD</option>
                <option value="DENSE">DENSE</option>
              </select>
              <div className="flex items-center gap-4 flex-wrap">
                <label className="text-sm text-slate-300"><input type="checkbox" checked={nodeForm.isCoastal} onChange={(e) => setNodeForm((s) => ({ ...s, isCoastal: e.target.checked }))} className="mr-2" />isCoastal</label>
                <label className="text-sm text-slate-300"><input type="checkbox" checked={nodeForm.isMountain} onChange={(e) => setNodeForm((s) => ({ ...s, isMountain: e.target.checked }))} className="mr-2" />isMountain</label>
                <label className="text-sm text-slate-300"><input type="checkbox" checked={nodeForm.isActive} onChange={(e) => setNodeForm((s) => ({ ...s, isActive: e.target.checked }))} className="mr-2" />isActive</label>
                <label className="text-sm text-slate-300"><input type="checkbox" checked={nodeForm.isVolatile} onChange={(e) => setNodeForm((s) => ({ ...s, isVolatile: e.target.checked }))} className="mr-2" />isVolatile</label>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setNodeFormOpen(false)} className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded">Cancel</button>
              <button onClick={submitNodeForm} disabled={nodeFormSaving} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded disabled:opacity-40">{nodeFormSaving ? 'Saving...' : 'Save'}</button>
            </div>
          </motion.div>
        </div>
      )}

      {selectedSpatial && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto shadow-2xl">
            <div className="sticky top-0 bg-slate-900 border-b border-slate-800 p-6 flex justify-between items-center z-10">
              <div>
                <h3 className="text-xl font-bold text-white flex items-center gap-2">
                  <Map className="w-5 h-5 text-emerald-500" />
                  {selectedSpatial.name} <span className="text-slate-400 text-sm font-normal font-mono ml-2">{selectedSpatial.pcode}</span>
                </h3>
                <p className="text-sm text-slate-400 mt-1">Spatial Unit IDW diagnostics and current weather</p>
              </div>
              <button onClick={() => { setSelectedSpatial(null); setSpatialDetails(null); }} className="p-2 text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded-xl transition-colors"><X className="w-5 h-5" /></button>
            </div>

            <div className="p-6 space-y-6">
              {detailsLoading ? (
                <div className="py-12 flex justify-center"><RefreshCw className="w-8 h-8 text-emerald-500 animate-spin" /></div>
              ) : spatialDetails ? (
                <>
                  <div className="bg-slate-800/50 rounded-xl border border-slate-700/50 p-5">
                    <h4 className="text-sm font-semibold text-slate-300 uppercase tracking-wider mb-4 flex items-center gap-2"><CloudRain className="w-4 h-4 text-emerald-400" /> Computed live weather</h4>
                    {spatialDetails.insight ? (
                      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                        <div><p className="text-slate-500 text-xs text-center">Temp</p><p className="text-lg font-bold text-white text-center">{spatialDetails.insight.weightedTempC?.toFixed?.(1) ?? 'N/A'}°C</p></div>
                        <div><p className="text-slate-500 text-xs text-center">Humidity</p><p className="text-lg font-bold text-white text-center">{spatialDetails.insight.weightedHumidityPct?.toFixed?.(1) ?? 'N/A'}%</p></div>
                        <div><p className="text-slate-500 text-xs text-center">Precip</p><p className="text-lg font-bold text-white text-center">{spatialDetails.insight.weightedPrecipitationMm?.toFixed?.(2) ?? 'N/A'}mm</p></div>
                        <div><p className="text-slate-500 text-xs text-center">Coverage</p><p className="text-lg font-bold text-white text-center">{spatialDetails.insight.weightCoverage != null ? `${(spatialDetails.insight.weightCoverage * 100).toFixed(1)}%` : 'N/A'}</p></div>
                      </div>
                    ) : (
                      <p className="text-slate-500 text-sm text-center py-2">No weather currently computed for this unit.</p>
                    )}
                  </div>

                  <div>
                    <h4 className="text-sm font-semibold text-slate-300 uppercase tracking-wider mb-4 flex items-center gap-2"><Activity className="w-4 h-4 text-blue-400" /> IDW node mappings ({spatialDetails.mappings.length})</h4>
                    <div className="bg-slate-800/50 rounded-xl border border-slate-700/50 overflow-hidden">
                      <table className="w-full text-left text-sm text-slate-300">
                        <thead className="bg-slate-800 text-slate-400 font-medium">
                          <tr>
                            <th className="px-4 py-3">Rank</th>
                            <th className="px-4 py-3">Node Code</th>
                            <th className="px-4 py-3">Distance</th>
                            <th className="px-4 py-3">IDW Weight</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-700/50">
                          {spatialDetails.mappings.map((m) => (
                            <tr key={m.id} className={m.isPrimary ? 'bg-emerald-500/5' : ''}>
                              <td className="px-4 py-3 font-medium">#{m.rank} {m.isPrimary && <span className="ml-2 text-[10px] bg-emerald-500/20 text-emerald-400 px-1.5 py-0.5 rounded uppercase">Primary</span>}</td>
                              <td className="px-4 py-3 font-mono text-emerald-400">{m.weatherNodeCode}</td>
                              <td className="px-4 py-3 font-mono">{m.distanceKm.toFixed(2)} km</td>
                              <td className="px-4 py-3 font-mono">{(m.idwWeight * 100).toFixed(2)}%</td>
                            </tr>
                          ))}
                          {spatialDetails.mappings.length === 0 && (
                            <tr><td colSpan={4} className="px-4 py-6 text-center text-slate-500">No mappings synthesized yet.</td></tr>
                          )}
                        </tbody>
                      </table>
                    </div>
                    <div className="mt-3 flex gap-2 items-start bg-blue-500/10 text-blue-300 p-3 rounded-lg text-xs">
                      <Info className="w-4 h-4 shrink-0 mt-0.5" />
                      <p>IDW interpolates live weather for this spatial unit using nearest active weather nodes and stored mapping weights.</p>
                    </div>
                  </div>
                </>
              ) : null}
            </div>
          </motion.div>
        </div>
      )}

      {selectedNode && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-2xl max-h-[90vh] overflow-hidden flex flex-col shadow-2xl">
            <div className="bg-slate-900 border-b border-slate-800 p-6 flex justify-between items-center z-10">
              <div>
                <h3 className="text-xl font-bold text-white flex items-center gap-2">
                  <Activity className="w-5 h-5 text-cyan-500" />
                  Live Telemetry
                  <span className="text-cyan-400 text-sm font-normal font-mono ml-2 px-2 py-0.5 bg-cyan-500/10 rounded">{selectedNode.code}</span>
                </h3>
                {nodeTelemetry?.sourceApi && <p className="text-xs text-slate-400 mt-1">Source API: {nodeTelemetry.sourceApi}</p>}
              </div>
              <button onClick={() => { setSelectedNode(null); setNodeTelemetry(null); setNodeTelemetrySummary(null); }} className="p-2 text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded-xl transition-colors"><X className="w-5 h-5" /></button>
            </div>

            <div className="p-0 overflow-y-auto flex-1 bg-[#1e1e1e]">
              {detailsLoading ? (
                <div className="py-20 flex flex-col items-center gap-4"><RefreshCw className="w-8 h-8 text-cyan-500 animate-spin" /><span className="text-slate-400 text-sm animate-pulse">Fetching from live cache...</span></div>
              ) : nodeTelemetry && nodeTelemetry.rawPayload ? (
                <div>
                  {nodeTelemetrySummary && (
                    <div className="p-4 border-b border-slate-700 bg-slate-900/70 text-slate-200 text-sm grid grid-cols-2 md:grid-cols-4 gap-3">
                      <div><p className="text-xs text-slate-400">Payload Type</p><p className="font-semibold">{nodeTelemetrySummary.rawPayloadType || 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Payload Entries</p><p className="font-semibold">{nodeTelemetrySummary.rawPayloadEntryCount ?? 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Fetched At</p><p className="font-semibold">{nodeTelemetrySummary.fetchedAt || 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Source API</p><p className="font-semibold">{nodeTelemetrySummary.sourceApi || 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Temp (cache)</p><p className="font-semibold">{nodeTelemetrySummary.tempC ?? 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Humidity (cache)</p><p className="font-semibold">{nodeTelemetrySummary.humidityPct ?? 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">Weather Code (cache)</p><p className="font-semibold">{nodeTelemetrySummary.weatherCode ?? 'N/A'}</p></div>
                      <div><p className="text-xs text-slate-400">First Entry Time</p><p className="font-semibold">{nodeTelemetrySummary.firstEntryTime || 'N/A'}</p></div>
                    </div>
                  )}
                  {telemetryArrayInfo && (
                    <div className="p-4 border-b border-slate-700 bg-slate-900/70 text-slate-200 text-sm space-y-1">
                      <p className="text-amber-300 font-medium">This payload is an array of location snapshots, not a single object.</p>
                      <p>Entries: {telemetryArrayInfo.count}</p>
                      <p>First entry time: {telemetryArrayInfo.firstTime ?? 'N/A'}</p>
                      <p>First entry temp/humidity/weatherCode: {telemetryArrayInfo.firstTemp ?? 'N/A'} / {telemetryArrayInfo.firstHumidity ?? 'N/A'} / {telemetryArrayInfo.firstWeatherCode ?? 'N/A'}</p>
                    </div>
                  )}
                  <pre className="p-6 text-sm font-mono text-emerald-400 whitespace-pre-wrap">{JSON.stringify(parsedTelemetry ?? nodeTelemetry.rawPayload, null, 2)}</pre>
                </div>
              ) : (
                <div className="p-8 text-center text-slate-500">
                  <FileJson className="w-12 h-12 mx-auto mb-4 opacity-50" />
                  <p>No telemetry payload found.</p>
                  <p className="text-xs mt-2">Run weather sync to populate live node cache.</p>
                </div>
              )}
            </div>
          </motion.div>
        </div>
      )}
    </div>
  );
};

export default AdminSystemPage;