package ooo.klae.connex.backend.mappers;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.ibatis.type.JdbcType;

import ooo.klae.connex.backend.util.SearchNormalizer;

/** Explicit search-phone binding; never globally registered for String. */
public class SearchPhoneTypeHandler extends AiIdentifierUtf8TextTypeHandler {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String value, JdbcType jdbcType) throws SQLException {
        super.setNonNullParameter(statement, index, SearchNormalizer.phone(value), jdbcType);
    }
}
