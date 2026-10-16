package ooo.klae.connex.backend.mappers;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.ibatis.type.JdbcType;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;

/** Explicit parameter-only canonical literal binding; never registered globally for String. */
public class AiIdentifierLiteralKeyTypeHandler extends AiIdentifierUtf8TextTypeHandler {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String name, JdbcType jdbcType) throws SQLException {
        super.setNonNullParameter(statement, index, AiIdentifierLookupKeys.fromName(name).literal(), jdbcType);
    }
}
