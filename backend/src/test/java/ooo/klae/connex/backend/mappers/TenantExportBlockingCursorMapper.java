package ooo.klae.connex.backend.mappers;

import java.util.Map;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.ResultSetType;

/** Test-only streaming cursor whose second row remains inside an exact MySQL sleep query. */
public interface TenantExportBlockingCursorMapper {

    @Select("""
        SELECT
            CONNECTION_ID() AS connectionId,
            REPEAT('x', 65536) AS flushPayload,
            CASE export_rows.sequenceId
                WHEN 1 THEN 0
                ELSE SLEEP(30)
            END AS waitResult
        FROM (
            SELECT 1 AS sequenceId
            UNION ALL
            SELECT 2 AS sequenceId
        ) export_rows
        """)
    @Options(
        fetchSize = Integer.MIN_VALUE,
        resultSetType = ResultSetType.FORWARD_ONLY)
    Cursor<Map<String, Object>> queryCursor();
}
