-- Run with: trace_processor_shell query trace.pftrace -f frame-pacing-analysis.sql
-- Surface timeline ends mean GPU/post completion. Join its DisplayFrame to measure presentation.
WITH inputs AS (
    SELECT id, name, ts, dur FROM slice WHERE name GLOB 'misq-input-*' AND dur > 0
), presented AS (
    SELECT i.id, i.name, d.ts + d.dur AS presented_at, f.jank_type
    FROM actual_frame_timeline_slice f
    JOIN inputs i ON f.ts >= i.ts AND f.ts < i.ts + i.dur
    JOIN actual_frame_timeline_slice d ON d.display_frame_token = f.display_frame_token
        AND d.surface_frame_token IS NULL AND d.dur > 0
    WHERE f.layer_name GLOB '*net.neomenia.misqnotes.probe/jp.handwriting.note.PerformanceActivity#*'
        AND f.present_type != 'Dropped Frame'
), gaps AS (
    SELECT *, (presented_at - LAG(presented_at) OVER (PARTITION BY id ORDER BY presented_at)) / 1e6 AS gap_ms
    FROM presented
)
SELECT name, COUNT(*) AS frames, COUNT(gap_ms) AS intervals,
    ROUND(PERCENTILE(gap_ms, 50), 3) AS p50_ms,
    ROUND(PERCENTILE(gap_ms, 95), 3) AS p95_ms,
    ROUND(PERCENTILE(gap_ms, 99), 3) AS p99_ms,
    SUM(gap_ms > 25) AS intervals_over_25_ms,
    SUM(jank_type GLOB '*App Deadline Missed*') AS app_deadline_misses
FROM gaps GROUP BY name;
