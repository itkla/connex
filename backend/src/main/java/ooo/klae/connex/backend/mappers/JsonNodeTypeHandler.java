package ooo.klae.connex.backend.mappers;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maps MySQL JSON values to Jackson 3 trees without converting them through untyped maps. */
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, JsonNode parameter, JdbcType jdbcType) throws SQLException {
        statement.setString(index, parameter.toString());
    }

    @Override
    public JsonNode getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private JsonNode parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new SQLException("Stored JSON could not be read", exception);
        }
    }
}
