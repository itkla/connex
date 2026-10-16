package ooo.klae.connex.backend.mappers;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;

/** Explicit parameter-only canonical literal binding; never registered globally for String. */
public class AiIdentifierLiteralKeyTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String name, JdbcType jdbcType) throws SQLException {
        statement.setString(index, AiIdentifierLookupKeys.fromName(name).literal());
    }

    @Override
    public String getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getString(columnIndex);
    }
}
