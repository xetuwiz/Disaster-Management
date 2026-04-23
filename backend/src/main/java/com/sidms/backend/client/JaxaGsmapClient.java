package com.sidms.backend.client;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and parses JAXA GSMaP_NRT global rainfall binary grids via FTP.
 *
 * Data product: GSMaP_NRT v8 — 0.1° global grid, 1-hour accumulation
 * FTP server: hokusai.eorc.jaxa.jp (credentials required)
 * Binary format: 3600 × 1200 IEEE 754 single-precision floats, little-endian
 * Row 0 = 59.95°N, Col 0 = 0.05°E, step = 0.1°
 *
 * Only cells within the Sri Lanka bounding box are stored to DB.
 * Old records (> 48h) are pruned after each successful insert.
 */
@Component
@Slf4j
public class JaxaGsmapClient {

    // ── FTP config (env-overridable) ─────────────────────────────────────────
    @Value("${jaxa.ftp.host:hokusai.eorc.jaxa.jp}")
    private String ftpHost;

    @Value("${jaxa.ftp.user:}")
    private String ftpUser;

    @Value("${jaxa.ftp.pass:}")
    private String ftpPass;

    // ── Grid constants ───────────────────────────────────────────────────────
    private static final int NCOLS = 3600;
    private static final int NROWS = 1200;
    private static final int EXPECTED_GRID_BYTES = NCOLS * NROWS * 4;
    private static final double LON_FIRST = 0.05;
    private static final double LAT_FIRST = 59.95;
    private static final double STEP = 0.1;

    // Sri Lanka bounding box
    private static final double SL_LAT_MIN = 5.9;
    private static final double SL_LAT_MAX = 10.0;
    private static final double SL_LON_MIN = 79.5;
    private static final double SL_LON_MAX = 82.0;

    // FTP paths and file pattern candidates — gauge-calibrated products listed
    // first for priority
    private static final List<String> FTP_DIR_CANDIDATES = List.of(
            "/realtime_ver/v8/hourly_G/",
            "/realtime_ver/v8/latest/",
            "/realtime_ver/v8/hourly/",
            "/now/latest/");
    private static final List<String> FILE_SUFFIX_CANDIDATES = List.of(".dat.gz", ".dat");
    private static final List<String> FILE_PREFIX_CANDIDATES = List.of(
            "gsmap_nrt.",
            "gsmap_gauge_nrt.",
            "gsmap_now.",
            "gsmap_gauge_now.",
            "gsmap_gauge.");
    private static final Pattern TS_TOKEN_PATTERN = Pattern.compile("(\\d{8}\\.\\d{4})");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd.HHmm");

    private static final int FTP_RETRY_COUNT = 3;
    private static final long FTP_RETRY_SLEEP = 60_000L;
    private static final int FTP_CONNECT_TIMEOUT_MS = 10_000;
    private static final int FTP_DATA_TIMEOUT_MS = 30_000;
    private static final int FTP_CONTROL_KEEPALIVE_TIMEOUT_S = 30;

    // Mapping from filename prefix to product type label stored in DB
    private static final java.util.Map<String, String> PREFIX_TO_PRODUCT = java.util.Map.of(
            "gsmap_gauge_nrt.", "GSMaP_Gauge_NRT",
            "gsmap_nrt.", "GSMaP_NRT",
            "gsmap_gauge_now.", "GSMaP_Gauge_NOW",
            "gsmap_now.", "GSMaP_NOW",
            "gsmap_gauge.", "GSMaP_Gauge");

    private final JdbcTemplate jdbcTemplate;

    public JaxaGsmapClient(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // Inner data class
    // =========================================================================

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class JaxaGridPoint {
        private double gridLat;
        private double gridLon;
        private double rainfallMm;
        private String productType;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Downloads the GSMaP grid for {@code targetHour}, extracts Sri Lanka cells,
     * bulk-inserts into {@code jaxa_rain_grid}, then prunes records older than 48h.
     *
     * @param targetHour the UTC hour to fetch (truncated to hour precision)
     */
    public void fetchAndStore(LocalDateTime targetHour) {
        Path tmpFile = null;

        try {
            tmpFile = Files.createTempFile("jaxa_", ".dat.gz");
            String downloadedFileName = connectWithRetry(tmpFile, targetHour);

            // Derive the actual observation timestamp from the downloaded filename
            LocalDateTime fileTimestamp = parseTimestampFromFileName(downloadedFileName);
            if (fileTimestamp == null) {
                fileTimestamp = targetHour;
                log.warn("[JAXA] Could not parse timestamp from filename '{}' — falling back to targetHour {}",
                        downloadedFileName, targetHour);
            }

            // Derive product type from filename prefix
            String productType = deriveProductType(downloadedFileName);

            log.info("[JAXA] Decompressing and parsing binary grid for {} (file: {}, product: {})",
                    fileTimestamp, downloadedFileName, productType);
            byte[] rawBytes = readRawBytes(tmpFile, downloadedFileName);

            List<JaxaGridPoint> points = extractSriLankaCells(rawBytes, productType);
            log.info("[JAXA] Extracted {} grid points within Sri Lanka bounding box", points.size());

            if (points.isEmpty()) {
                throw new RuntimeException("[JAXA] No Sri Lanka grid points extracted from " + downloadedFileName);
            }

            bulkInsert(points, fileTimestamp);
            pruneOldRecords();
            log.info("[JAXA] Stored {} grid points for {} (product: {})", points.size(), fileTimestamp, productType);

        } catch (Exception e) {
            log.error("[JAXA] fetchAndStore failed for {}: {}", targetHour, e.getMessage(), e);
            throw new RuntimeException("[JAXA] fetchAndStore failed: " + e.getMessage(), e);
        } finally {
            deleteTempFile(tmpFile);
        }
    }

    // =========================================================================
    // Private — FTP connect + download with retry
    // =========================================================================

    private String connectWithRetry(Path tmpFile, LocalDateTime targetHour)
            throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= FTP_RETRY_COUNT; attempt++) {
            FTPClient ftp = new FTPClient();
            try {
                return downloadFile(ftp, tmpFile, targetHour);
            } catch (Exception e) {
                lastException = e;
                log.warn("[JAXA] FTP attempt {}/{} failed: {} — retrying in {}s",
                        attempt, FTP_RETRY_COUNT, e.getMessage(), FTP_RETRY_SLEEP / 1000);
                if (attempt < FTP_RETRY_COUNT) {
                    Thread.sleep(FTP_RETRY_SLEEP);
                }
            } finally {
                disconnectFtp(ftp);
            }
        }
        throw new RuntimeException("[JAXA] All " + FTP_RETRY_COUNT
                + " FTP attempts failed", lastException);
    }

    private String downloadFile(FTPClient ftp, Path tmpFile, LocalDateTime targetHour)
            throws Exception {
        if (ftpUser == null || ftpUser.isBlank() || ftpPass == null || ftpPass.isBlank()) {
            throw new RuntimeException("[JAXA] FTP credentials are missing (JAXA_FTP_USER/JAXA_FTP_PASS)");
        }

        log.info("[JAXA] Connecting to FTP @{}", ftpHost);
        ftp.setConnectTimeout(FTP_CONNECT_TIMEOUT_MS);
        ftp.connect(ftpHost);
        boolean loggedIn = ftp.login(ftpUser, ftpPass);
        if (!loggedIn) {
            throw new RuntimeException("[JAXA] FTP login failed for host " + ftpHost);
        }
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();
        ftp.setDataTimeout(java.time.Duration.ofMillis(FTP_DATA_TIMEOUT_MS));
        ftp.setControlKeepAliveTimeout(FTP_CONTROL_KEEPALIVE_TIMEOUT_S);

        String targetToken = targetHour.format(TS_FMT);
        String selectedDir = null;
        String selectedFile = null;

        for (String dir : FTP_DIR_CANDIDATES) {
            FTPFile[] files = ftp.listFiles(dir);
            if (files == null || files.length == 0) {
                log.warn("[JAXA] No files found in {}", dir);
                continue;
            }

            String chosen = selectFile(files, targetHour, targetToken);
            if (chosen != null) {
                selectedDir = dir;
                selectedFile = chosen;
                break;
            }

            log.warn("[JAXA] No timestamped GSMaP .dat file found in {} for target {}", dir, targetToken);
        }

        if (selectedDir == null || selectedFile == null) {
            throw new RuntimeException("[JAXA] No compatible files found in any candidate directory: "
                    + String.join(", ", FTP_DIR_CANDIDATES));
        }

        log.info("[JAXA] Downloading {}{}", selectedDir, selectedFile);
        try (InputStream is = ftp.retrieveFileStream(selectedDir + selectedFile);
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmpFile.toFile()))) {
            if (is == null) {
                throw new RuntimeException("[JAXA] FTP retrieve returned null stream for " + selectedFile);
            }
            is.transferTo(bos);
        }

        if (!ftp.completePendingCommand()) {
            throw new RuntimeException("[JAXA] FTP completePendingCommand failed for " + selectedFile);
        }
        log.info("[JAXA] Downloaded {} bytes to temp file", Files.size(tmpFile));
        return selectedFile;
    }

    /**
     * Selects the exact target file if present; otherwise the one with the
     * lexicographically closest timestamp token (all files are named consistently).
     */
    private String selectFile(FTPFile[] files, LocalDateTime targetHour, String targetToken) {
        String best = null;
        long bestDiff = Long.MAX_VALUE;

        for (FTPFile f : files) {
            String name = f.getName();
            if (!isSupportedDataFile(name))
                continue;

            String token = extractTimestampToken(name);
            if (token == null)
                continue;

            try {
                LocalDateTime fileTime = LocalDateTime.parse(token, TS_FMT);
                long diff = Math.abs(java.time.Duration.between(targetHour, fileTime).toMinutes());
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = name;
                }
                if (diff == 0)
                    return name; // exact timestamp match
            } catch (Exception ignored) {
                // Skip files that do not have a parseable timestamp token.
            }
        }

        if (best == null)
            return null;

        log.warn("[JAXA] Exact timestamp {} not found — using closest available: {} ({}min off)",
                targetToken, best, bestDiff);
        return best;
    }

    private boolean isSupportedDataFile(String name) {
        boolean hasAllowedPrefix = false;
        for (String prefix : FILE_PREFIX_CANDIDATES) {
            if (name.startsWith(prefix)) {
                hasAllowedPrefix = true;
                break;
            }
        }
        if (!hasAllowedPrefix)
            return false;

        for (String suffix : FILE_SUFFIX_CANDIDATES) {
            if (name.endsWith(suffix))
                return true;
        }
        return false;
    }

    private String extractTimestampToken(String fileName) {
        Matcher matcher = TS_TOKEN_PATTERN.matcher(fileName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // =========================================================================
    // Private — filename-derived metadata
    // =========================================================================

    /**
     * Parses the observation timestamp from the downloaded filename.
     * e.g. "gsmap_nrt.20260422.1300.dat.gz" → 2026-04-22T13:00
     */
    private LocalDateTime parseTimestampFromFileName(String fileName) {
        String token = extractTimestampToken(fileName);
        if (token == null)
            return null;
        try {
            return LocalDateTime.parse(token, TS_FMT);
        } catch (Exception e) {
            log.warn("[JAXA] Failed to parse timestamp token '{}' from file '{}'", token, fileName);
            return null;
        }
    }

    /**
     * Derives the product type label from the filename prefix.
     * e.g. "gsmap_gauge_nrt.20260422.1300.dat.gz" → "GSMaP_Gauge_NRT"
     * Falls back to "GSMaP_NRT" if no known prefix matches.
     */
    private String deriveProductType(String fileName) {
        for (java.util.Map.Entry<String, String> entry : PREFIX_TO_PRODUCT.entrySet()) {
            if (fileName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        log.warn("[JAXA] Unknown product prefix in '{}' — defaulting to GSMaP_NRT", fileName);
        return "GSMaP_NRT";
    }

    // =========================================================================
    // Private — binary parsing
    // =========================================================================

    /** Reads the downloaded payload as either gzipped or plain binary bytes. */
    private byte[] readRawBytes(Path file, String downloadedFileName) throws Exception {
        byte[] raw;
        if (downloadedFileName.endsWith(".gz")) {
            try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(file))) {
                raw = gzis.readAllBytes();
            }
        } else if (downloadedFileName.endsWith(".dat")) {
            raw = Files.readAllBytes(file);
        } else {
            throw new RuntimeException("[JAXA] Unsupported file extension: " + downloadedFileName);
        }

        if (raw.length != EXPECTED_GRID_BYTES) {
            throw new RuntimeException("[JAXA] Unexpected payload size " + raw.length
                    + " bytes (expected " + EXPECTED_GRID_BYTES + ") for " + downloadedFileName);
        }
        return raw;
    }

    /**
     * Parses the flat binary float array and returns only cells inside
     * the Sri Lanka bounding box.
     */
    private List<JaxaGridPoint> extractSriLankaCells(byte[] raw, String productType) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // Use ceil/floor so we stay strictly inside configured bounds.
        int rowMin = (int) Math.ceil((LAT_FIRST - SL_LAT_MAX) / STEP);
        int rowMax = (int) Math.floor((LAT_FIRST - SL_LAT_MIN) / STEP);
        int colMin = (int) Math.ceil((SL_LON_MIN - LON_FIRST) / STEP);
        int colMax = (int) Math.floor((SL_LON_MAX - LON_FIRST) / STEP);

        // Safety clamp to grid bounds
        rowMin = Math.max(0, rowMin);
        rowMax = Math.min(NROWS - 1, rowMax);
        colMin = Math.max(0, colMin);
        colMax = Math.min(NCOLS - 1, colMax);

        List<JaxaGridPoint> points = new ArrayList<>((rowMax - rowMin + 1) * (colMax - colMin + 1));

        for (int row = rowMin; row <= rowMax; row++) {
            double gridLat = LAT_FIRST - row * STEP;
            for (int col = colMin; col <= colMax; col++) {
                double gridLon = LON_FIRST + col * STEP;
                int floatIndex = row * NCOLS + col;
                int byteOffset = floatIndex * 4;

                if (byteOffset + 4 > raw.length)
                    continue;

                float rainfall = buffer.getFloat(byteOffset);
                if (rainfall >= 0.0f) { // negative sentinel = missing/fill value
                    points.add(new JaxaGridPoint(gridLat, gridLon, rainfall, productType));
                }
            }
        }
        return points;
    }

    // =========================================================================
    // Private — database operations
    // =========================================================================

    /**
     * Bulk-inserts grid points using JdbcTemplate.batchUpdate().
     */
    private void bulkInsert(List<JaxaGridPoint> points, LocalDateTime timestampUtc) {
        String sql = """
                INSERT INTO jaxa_rain_grid
                    (timestamp_utc, grid_lat, grid_lon, rainfall_mm, product_type)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (timestamp_utc, grid_lat, grid_lon) DO NOTHING
                """;

        List<Object[]> batch = new ArrayList<>(points.size());
        for (JaxaGridPoint p : points) {
            batch.add(new Object[] {
                    timestampUtc,
                    p.getGridLat(),
                    p.getGridLon(),
                    p.getRainfallMm(),
                    p.getProductType()
            });
        }

        int[][] result = jdbcTemplate.batchUpdate(sql, batch, 500,
                (ps, args) -> {
                    ps.setObject(1, args[0]); // timestamp
                    ps.setDouble(2, (double) args[1]); // grid_lat
                    ps.setDouble(3, (double) args[2]); // grid_lon
                    ps.setDouble(4, (double) args[3]); // rainfall_mm
                    ps.setString(5, (String) args[4]); // product_type
                });

        log.info("[JAXA] Batch insert complete — {} batches", result.length);
    }

    /**
     * Removes grid records older than 48 hours to prevent unbounded table growth.
     */
    private void pruneOldRecords() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM jaxa_rain_grid WHERE timestamp_utc < NOW() - INTERVAL '48 hours'");
        if (deleted > 0) {
            log.info("[JAXA] Pruned {} old grid records (> 48h)", deleted);
        }
    }

    // =========================================================================
    // Private — cleanup helpers
    // =========================================================================

    private void disconnectFtp(FTPClient ftp) {
        if (ftp != null && ftp.isConnected()) {
            try {
                ftp.logout();
                ftp.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private void deleteTempFile(Path tmpFile) {
        if (tmpFile != null) {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (Exception ignored) {
            }
        }
    }
}
