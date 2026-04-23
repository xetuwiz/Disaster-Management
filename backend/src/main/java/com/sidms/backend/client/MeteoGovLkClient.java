package com.sidms.backend.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads and parses the Meteo.gov.lk 3-hourly observation Excel file.
 *
 * Excel URL: https://meteo.gov.lk/excels/3hourly.xlsx
 * Format:    XLSX, first row = headers, subsequent rows = station readings.
 * Timezone:  Report_Time is Sri Lanka Standard Time (UTC+5:30) — converted to UTC on parse.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MeteoGovLkClient {

    private static final String EXCEL_URL = "https://meteo.gov.lk/excels/3hourly.xlsx";

    // Exact header strings as they appear in the Excel file
    private static final String COL_STATION_ID   = "Station_ID";
    private static final String COL_REPORT_TIME  = "Report_Time";
    private static final String COL_RAINFALL     = "Rainfall (mm)";
    private static final String COL_TEMPERATURE  = "Temperature ( C )";
    private static final String COL_HUMIDITY     = "RH (%)";
    private static final String COL_WEATHER_TYPE = "weathertype";

    // Report_Time format: "2026-04-21  1430" (double-space between date and time)
    private static final DateTimeFormatter SLST_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm");

    private final RestTemplate restTemplate;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Fetches the latest 3-hourly readings from all Meteo.gov.lk stations.
     * Returns an empty list on any network or parse error.
     *
     * @return list of parsed station readings; never null
     */
    public List<MeteoStationReading> fetchLatestReadings() {
        List<MeteoStationReading> readings = new ArrayList<>();
        XSSFWorkbook workbook = null;

        try {
            log.info("[MeteoGovLk] Downloading 3-hourly Excel from {}", EXCEL_URL);
            byte[] excelBytes = restTemplate.getForObject(EXCEL_URL, byte[].class);

            if (excelBytes == null || excelBytes.length == 0) {
                log.warn("[MeteoGovLk] Empty response from Excel URL");
                return readings;
            }

            workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Build header-name → column-index map from the first row
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                log.warn("[MeteoGovLk] Excel has no header row");
                return readings;
            }
            Map<String, Integer> colIndex = buildColumnIndex(headerRow);
            log.info("[MeteoGovLk] Parsed header map: {}", colIndex.keySet());

            // Process data rows (rowNum >= 1)
            int rowCount = sheet.getLastRowNum();
            for (int rowNum = 1; rowNum <= rowCount; rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                try {
                    MeteoStationReading reading = parseRow(row, colIndex);
                    if (reading != null) readings.add(reading);
                } catch (Exception e) {
                    log.warn("[MeteoGovLk] Skipping row {} — parse error: {}", rowNum, e.getMessage());
                }
            }

            log.info("[MeteoGovLk] Parsed {} station readings", readings.size());

        } catch (Exception e) {
            log.warn("[MeteoGovLk] Failed to fetch or parse Excel: {}", e.getMessage(), e);
        } finally {
            if (workbook != null) {
                try {
                    workbook.close();
                } catch (Exception ignored) {}
            }
        }

        return readings;
    }

    // =========================================================================
    // Inner data record
    // =========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MeteoStationReading {
        private String        stationId;
        private LocalDateTime timestampUtc;
        private Double        temperatureC;
        private Double        rainfallMm;
        private Double        humidityPct;
        private String        weatherType;
        private String        rawRow;
    }

    // =========================================================================
    // Private — row parsing
    // =========================================================================

    private MeteoStationReading parseRow(Row row, Map<String, Integer> colIndex) {
        String stationId = getCellString(row, colIndex, COL_STATION_ID);
        if (stationId == null || stationId.isBlank()) return null;

        // Station_ID may come as numeric (43404.0) — strip the decimal part
        if (stationId.contains(".")) {
            stationId = stationId.substring(0, stationId.indexOf('.'));
        }

        String reportTimeStr = getCellString(row, colIndex, COL_REPORT_TIME);
        if (reportTimeStr == null || reportTimeStr.isBlank()) return null;

        // Parse SLST timestamp and convert to UTC (SLST = UTC+5:30)
        LocalDateTime slstTime = LocalDateTime.parse(
                reportTimeStr.trim().replace("  ", " "),
                SLST_FORMATTER);
        LocalDateTime utcTime = slstTime.minusHours(5).minusMinutes(30);

        String rawRow = buildRawRow(row, colIndex);

        return MeteoStationReading.builder()
                .stationId(stationId)
                .timestampUtc(utcTime)
                .temperatureC(getDouble(row, colIndex, COL_TEMPERATURE))
                .rainfallMm(getDouble(row, colIndex, COL_RAINFALL))
                .humidityPct(getDouble(row, colIndex, COL_HUMIDITY))
                .weatherType(getCellString(row, colIndex, COL_WEATHER_TYPE))
                .rawRow(rawRow)
                .build();
    }

    // =========================================================================
    // Private — cell helpers
    // =========================================================================

    /** Builds a column-name → 0-based column-index map from the header row. */
    private Map<String, Integer> buildColumnIndex(Row headerRow) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i <= headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String name = cell.getStringCellValue().trim();
                if (!name.isEmpty()) index.put(name, i);
            }
        }
        return index;
    }

    /**
     * Returns the string value of a named cell, or null if the column is
     * absent, the cell is missing, or the cell is blank.
     */
    private String getCellString(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return null;

        Cell cell = row.getCell(idx);
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING  -> {
                String v = cell.getStringCellValue().trim();
                yield v.isEmpty() ? null : v;
            }
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                // Return as whole number string when value is integral
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default      -> null;
        };
    }

    /**
     * Returns the double value of a named cell, or null if absent/blank/non-numeric.
     */
    private Double getDouble(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return null;

        Cell cell = row.getCell(idx);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.STRING) {
            try {
                String v = cell.getStringCellValue().trim();
                if (v.equalsIgnoreCase("Trace")) {
                    return 0.0;
                }
                return v.isEmpty() ? null : Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Builds a compact raw-row string for audit storage. */
    private String buildRawRow(Row row, Map<String, Integer> colIndex) {
        StringBuilder sb = new StringBuilder();
        colIndex.forEach((name, idx) -> {
            Cell cell = row.getCell(idx);
            String val = cell != null ? getCellString(row, colIndex, name) : "";
            sb.append(name).append("=").append(val).append("|");
        });
        String raw = sb.toString();
        return raw.length() > 500 ? raw.substring(0, 500) : raw;
    }
}
