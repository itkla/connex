package ooo.klae.connex.backend.mappers;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Lossless UTF-8 byte transport; writes require CONVERT USING utf8mb4 and reads binary columns. */
public class AiIdentifierUtf8TextTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String name, JdbcType jdbcType) throws SQLException {
        statement.setBytes(index, name.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return decode(resultSet.getBytes(columnName));
    }

    @Override
    public String getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return decode(resultSet.getBytes(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return decode(statement.getBytes(columnIndex));
    }
    /** Decodes binary UTF-8 payloads independently of the JDBC connection charset. */
    public static String decode(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new SQLException("Invalid UTF-8 payload", exception);
        }
    }
}
